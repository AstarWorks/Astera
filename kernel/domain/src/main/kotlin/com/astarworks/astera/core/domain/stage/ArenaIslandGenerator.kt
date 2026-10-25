package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Integer block coordinate inside an arena world. */
public data class ArenaBlockPos(val x: Int, val y: Int, val z: Int)

/**
 * Pure description of a built sky-island arena: every block as a vendor-neutral block-data string
 * (`minecraft:red_banner[rotation=0]`). Absent coordinates are air. Produced by [ArenaIslandGenerator]
 * as a pure function of a [Stage] (ADR-0246), so it can be checked with no Minecraft on the classpath.
 *
 * @property volatileCells objective marker cells (wool / jewelry / TNT centres): the first build places a
 *   pedestal block there, but a repair pass leaves them alone because a game mode may have replaced them
 * @property problems layout / stage inconsistencies found while building (a spawn off the island, an
 *   objective region at the wrong height, ...); empty for a consistent stage
 */
public class ArenaBuildPlan(
    public val blocks: Map<ArenaBlockPos, String>,
    public val volatileCells: Set<ArenaBlockPos>,
    public val surfaceY: Int,
    public val problems: List<String>,
) {
    public val minX: Int = blocks.keys.minOf { it.x }
    public val maxX: Int = blocks.keys.maxOf { it.x }
    public val minY: Int = blocks.keys.minOf { it.y }
    public val maxY: Int = blocks.keys.maxOf { it.y }
    public val minZ: Int = blocks.keys.minOf { it.z }
    public val maxZ: Int = blocks.keys.maxOf { it.z }

    /** Block-data string at the coordinate, `null` for air. */
    public fun stateAt(x: Int, y: Int, z: Int): String? = blocks[ArenaBlockPos(x, y, z)]

    /** A player can stand on top of the block at the coordinate. */
    public fun isSolid(x: Int, y: Int, z: Int): Boolean = stateAt(x, y, z)?.let { !isPassable(it) } ?: false

    /** The column (x, z) has a solid top block at the surface layer. */
    public fun hasTop(x: Int, z: Int): Boolean = isSolid(x, surfaceY, z)

    /** Stable fingerprint of the whole build; a changed layout, stage anchor or generator changes it. */
    public fun digest(): Int = blocks.hashCode() * 31 + volatileCells.hashCode()

    public companion object {
        private val PASSABLE: Set<String> = setOf(
            "air", "short_grass", "tall_grass", "fern", "dandelion", "poppy", "azure_bluet", "cornflower",
            "allium", "oxeye_daisy", "pink_tulip", "red_tulip", "orange_tulip", "white_tulip", "blue_orchid",
            "lily_of_the_valley", "torch", "lantern",
        )

        /** True for blocks a player walks through (plants, banners, lanterns). */
        public fun isPassable(state: String): Boolean {
            val name = state.substringAfter(':').substringBefore('[')
            return name in PASSABLE || name.endsWith("_banner")
        }
    }
}

/**
 * Procedural sky-island arena (ADR-0246): the lobby's island look (grass over dirt over tapering stone with
 * stalactites) driven by a [Stage]'s [ArenaLayout], spawns, objective regions, pillars and landmarks.
 * Deterministic in `(layout.seed, stage)`; no Bukkit, no I/O.
 *
 * Per island: an irregular noise-driven outline (bays, a peninsula, terraced cliff flanks, overhangs and shoreline
 * shelves), terrain, then bridges between islands (an island block always wins over a bridge block), a
 * team-coloured rim and spawn pad with two banner posts on base islands, a pedestal under every objective
 * anchor (`core-*`, `jewelry-*`, `wool-*`, `tnt-spawn`, pillar footings, generator landmarks), a defended wall arc
 * behind each core, terrain features (stepped mounds, plaza ruins / boulders as cover, trees) kept clear of spawn pads,
 * anchors and the lanes leading to bridges, and a sprinkling of flowers away from everything a player has to see.
 */
public object ArenaIslandGenerator {

    /** Builds the plan for a stage that has a [Stage.layout]. */
    public fun generate(stage: Stage, parts: Map<String, ArenaStructurePart> = emptyMap()): ArenaBuildPlan {
        val layout = requireNotNull(stage.layout) { "stage '${stage.id.value}' has no arena layout" }
        return Builder(stage, layout, parts).build()
    }

    private class Builder(val stage: Stage, val layout: ArenaLayout, val parts: Map<String, ArenaStructurePart>) {
        private val palette = layout.theme.palette
        private fun material(slot: String): String = palette.getValue(slot)
        private val s = layout.surfaceY
        private val blocks = HashMap<ArenaBlockPos, String>(160_000)
        private val volatile = HashSet<ArenaBlockPos>()
        private val problems = ArrayList<String>()

        /** (x, z) -> the island whose top layer owns that column. */
        private val topCells = HashMap<Long, ArenaIsland>()
        private val reserved = HashSet<Long>()

        /** Columns that terrain features (mounds, cover, trees) must leave open: spawn pads, anchors, bridge lanes. */
        private val clear = HashSet<Long>()

        /** Columns already taken by a terrain feature. */
        private val occupied = HashSet<Long>()

        private fun key(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)

        private fun set(x: Int, y: Int, z: Int, state: String) {
            blocks[ArenaBlockPos(x, y, z)] = state
        }

        private fun get(x: Int, y: Int, z: Int): String? = blocks[ArenaBlockPos(x, y, z)]

        /** Overwrites the top layer of an island column; columns without an island are left alone. */
        private fun paintTop(x: Int, z: Int, state: String): Boolean {
            if (!topCells.containsKey(key(x, z))) return false
            set(x, s, z, state)
            return true
        }

        private fun hash(a: Int, b: Int, c: Int): Int {
            var h = layout.seed.toInt() xor (layout.seed ushr 32).toInt()
            h = h * 31 + a
            h = h * 31 + b
            h = h * 31 + c
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6d
            h = h xor (h ushr 12)
            h *= 0x297a2d39
            h = h xor (h ushr 15)
            return h
        }

        private fun rnd(a: Int, b: Int, c: Int): Double = (hash(a, b, c) ushr 8) / 16_777_216.0

        fun build(): ArenaBuildPlan {
            layout.islands.forEach { island(it) }
            layout.bridges.forEach { bridge(it) }
            shelves()
            rimTrim()
            layout.islands.filter { it.kind == ArenaIslandKind.BASE && it.team != null }.forEach { teamPad(it) }
            anchors()
            landmarks()
            checkSpawns()
            lanes()
            structures()
            silhouettes()
            features()
            settleGrass()
            deco()
            return ArenaBuildPlan(blocks, volatile, s, problems)
        }

        // ── terrain ──────────────────────────────────────────────────────

        /** Per-island outline tables (radius per 0.5 degree) so shelves and silhouettes can follow the rim. */
        private val rims = HashMap<String, DoubleArray>()

        /** Columns painted as a straight lane towards a bridge; spires and posts stay off them. */
        private val laneCells = HashSet<Long>()

        /** A disc of [margin] blocks around a point that must stay on its island. */
        private class Cover(val dist: Double, val angle: Double, val margin: Double)

        private fun angleDiff(a: Double, b: Double): Double {
            var d = (a - b) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            return d
        }

        private fun noise2(x: Double, z: Double, salt: Int): Double {
            val x0 = floor(x).toInt()
            val z0 = floor(z).toInt()
            val fx = x - x0
            val fz = z - z0
            val u = fx * fx * (3 - 2 * fx)
            val v = fz * fz * (3 - 2 * fz)
            fun h(i: Int, j: Int) = rnd(x0 + i, z0 + j, salt) * 2 - 1
            val a = h(0, 0) + (h(1, 0) - h(0, 0)) * u
            val b = h(0, 1) + (h(1, 1) - h(0, 1)) * u
            return a + (b - a) * v
        }

        private fun fbm(x: Double, z: Double, salt: Int): Double =
            noise2(x, z, salt) * 0.62 + noise2(x * 2.1 + 17.3, z * 2.1 - 9.1, salt + 1) * 0.38

        /** Stage points (spawns, anchors, authored parts) that must not be cut off by a bay or a lobe. */
        private fun coversFor(isl: ArenaIsland): List<Cover> {
            val out = ArrayList<Cover>()
            fun add(x: Double, z: Double, margin: Double) {
                val owner = layout.islands.minBy { hypot(x - it.x, z - it.z) / it.radius }
                if (owner.id != isl.id) return
                val dist = hypot(x - isl.x, z - isl.z)
                if (dist > isl.radius * 0.85 || dist < 0.5) return
                out += Cover(dist, atan2(z - isl.z, x - isl.x), margin)
            }
            for (spawn in stage.spawnPoints) if (spawn.role != SpawnRole.SPECTATOR) add(spawn.position.x, spawn.position.z, 7.0)
            for (region in stage.regions) {
                val name = region.name ?: continue
                if (name.startsWith("core-") || name.startsWith("jewelry-") || name.startsWith("wool-") || name == "tnt-spawn") {
                    add((region.min.x + region.max.x) / 2, (region.min.z + region.max.z) / 2, 6.0)
                }
            }
            for (mark in layout.landmarks) add(mark.x + 0.5, mark.z + 0.5, 5.0)
            for (pillar in stage.pillars) add(pillar.position.x, pillar.position.z, 5.0)
            for (placement in layout.structures) {
                val part = parts[placement.part] ?: continue
                val x0 = part.boxes.minOf { it.min.x }
                val x1 = part.boxes.maxOf { it.max.x }
                val z0 = part.boxes.minOf { it.min.z }
                val z1 = part.boxes.maxOf { it.max.z }
                // Sample the footprint outline every 4 blocks; interior points cannot be cut off before the outline is.
                for (lx in x0..x1 step 4) {
                    for (lz in z0..z1 step 4) {
                        val edge = lx - x0 < 4 || x1 - lx < 4 || lz - z0 < 4 || z1 - lz < 4
                        if (!edge) continue
                        val p = placement.transform(ArenaBlockPos(lx, 0, lz), s)
                        add(p.x.toDouble(), p.z.toDouble(), 3.0)
                    }
                }
                for ((lx, lz) in listOf(x1 to z1, x1 to z0, x0 to z1, x0 to z0)) {
                    val p = placement.transform(ArenaBlockPos(lx, 0, lz), s)
                    add(p.x.toDouble(), p.z.toDouble(), 3.0)
                }
            }
            return out
        }

        /**
         * Irregular outline (MAP-04): two octaves of noise around the circle make broad lobes, one or two narrow bays
         * (never towards a bridge) and a peninsula break the "squashed circle". Stage points keep their disc on land.
         */
        private fun rimTable(isl: ArenaIsland, random: Random): DoubleArray {
            val covers = coversFor(isl)
            val ox = random.nextDouble() * 200
            val oz = random.nextDouble() * 200
            val salt = isl.id.hashCode() and 0xffff
            val bridgeAngles = layout.bridges.filter { it.from == isl.id || it.to == isl.id }.map {
                val other = layout.islands.first { o -> o.id == (if (it.from == isl.id) it.to else it.from) }
                atan2((other.z - isl.z).toDouble(), (other.x - isl.x).toDouble())
            }
            fun free(a: Double) = bridgeAngles.all { abs(angleDiff(a, it)) > 0.6 }
            class Feature(val angle: Double, val width: Double, val amount: Double)
            val features = ArrayList<Feature>()
            val bays = if (isl.radius >= 24) 2 else 1
            var tries = 0
            while (features.size < bays && tries++ < 40) {
                val a = random.nextDouble() * 2 * PI
                if (!free(a) || features.any { abs(angleDiff(a, it.angle)) < 0.9 }) continue
                features += Feature(a, 0.16 + random.nextDouble() * 0.14, -(0.13 + random.nextDouble() * 0.09))
            }
            tries = 0
            while (isl.radius >= 14 && tries++ < 40) {
                val a = random.nextDouble() * 2 * PI
                if (!free(a) || features.any { abs(angleDiff(a, it.angle)) < 0.9 }) continue
                features += Feature(a, 0.12 + random.nextDouble() * 0.08, 0.12 + random.nextDouble() * 0.06)
                break
            }
            val table = DoubleArray(RIM_STEPS)
            for (i in 0 until RIM_STEPS) {
                val theta = i * 2 * PI / RIM_STEPS - PI
                val cx = cos(theta)
                val sz = sin(theta)
                var f = 1.0 + 0.16 * fbm(cx * 1.7 + ox, sz * 1.7 + oz, salt) + 0.06 * noise2(cx * 6.0 + oz, sz * 6.0 + ox, salt + 5)
                for (ft in features) {
                    val dt = angleDiff(theta, ft.angle) / ft.width
                    f += ft.amount * exp(-dt * dt)
                }
                var rr = isl.radius * f.coerceIn(0.8, 1.24)
                for (c in covers) {
                    val dTheta = angleDiff(theta, c.angle)
                    if (abs(dTheta) >= PI / 2) continue
                    val perp = c.dist * sin(dTheta)
                    if (abs(perp) >= c.margin) continue
                    val need = c.dist * cos(dTheta) + sqrt(c.margin * c.margin - perp * perp)
                    if (need > rr) rr = need
                }
                table[i] = min(rr, isl.radius * 1.24)
            }
            return table
        }

        private fun rimAt(table: DoubleArray, theta: Double): Double {
            val pos = (theta + PI) / (2 * PI) * RIM_STEPS
            val i = floor(pos).toInt()
            val f = pos - i
            return table[Math.floorMod(i, RIM_STEPS)] * (1 - f) + table[Math.floorMod(i + 1, RIM_STEPS)] * f
        }

        private fun island(isl: ArenaIsland) {
            val random = Random(layout.seed xor (isl.id.hashCode().toLong() shl 20))
            val table = rimTable(isl, random)
            rims[isl.id] = table
            val r = isl.radius
            fun rimAt(theta: Double): Double = rimAt(table, theta)
            val salt = isl.id.hashCode() and 0xffff
            val ox = random.nextDouble() * 300
            val oz = random.nextDouble() * 300

            // The underside tapers to 0.8 x radius for small islands; big ones grow thinner relative to their width
            // (a 30-block island is ~15 deep, not 24), which keeps the build and the kill plane in reach.
            val fullDepth = r * 0.8
            val maxDepth = if (fullDepth <= 10.0) fullDepth else 10.0 + (fullDepth - 10.0) * 0.35
            val stalacticeMax = min(r * 0.4, 7.0)
            val ext = ceil(r * 1.25).toInt()
            val step = if (r >= 20) 3 else 2
            for (dx in -ext..ext) {
                for (dz in -ext..ext) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    val rim = rimAt(atan2(dz.toDouble(), dx.toDouble()))
                    if (d > rim) continue
                    val x = isl.x + dx
                    val z = isl.z + dz
                    val previous = topCells.put(key(x, z), isl)
                    if (previous != null && previous.id != isl.id) {
                        problems += "islands '${previous.id}' and '${isl.id}' overlap at ($x, $z)"
                    }
                    val t = d / rim
                    // Cliff-and-taper profile: a steepness that changes across the island (sheer faces here, a gentle
                    // slope there), quantised into terraces so the flank reads as ledges rather than a smooth cone.
                    val steep = 2.0 + 1.4 * (noise2(x * 0.045 + ox, z * 0.045 + oz, salt + 11) * 0.5 + 0.5)
                    var raw = maxDepth * (1 - t.pow(steep)).coerceAtLeast(0.0).pow(0.7)
                    raw *= 1.0 + 0.2 * noise2(x * 0.11 + oz, z * 0.11 + ox, salt + 13)
                    val terraced = (raw / step).roundToInt() * step
                    val depth = min(terraced + 2 + (rnd(x, z, 1) * 2.0).toInt(), maxDepth.toInt() + 4)
                    for (dy in 0..depth) {
                        // Hollows eat into the outer flank below the lip so it overhangs.
                        if (dy >= 4 && dy < depth && t > 0.5 &&
                            noise2(x * 0.17 + dy * 0.31 + ox, z * 0.17 - dy * 0.27 + oz, salt + 17) > 0.5
                        ) continue
                        val state = when {
                            dy == 0 -> topBlock(isl, x, z, d, rim)
                            dy <= 2 -> shallow(isl)
                            else -> underside(x, s - dy, z, dy, depth)
                        }
                        set(x, s - dy, z, state)
                    }
                }
            }
            val stalactites = (r / 2.5).toInt() + 1
            repeat(stalactites) {
                val ang = random.nextDouble() * 2 * PI
                val t = 0.1 + random.nextDouble() * 0.65
                val x = isl.x + (cos(ang) * rimAt(ang) * t).roundToInt()
                val z = isl.z + (sin(ang) * rimAt(ang) * t).roundToInt()
                var bottom = s
                for (y in s downTo s - 80) if (get(x, y, z) != null) bottom = y
                val len = 3 + (random.nextDouble() * stalacticeMax).toInt()
                for (k in 1..len) {
                    val spread = (len - k).toDouble() / len
                    val y = bottom - k
                    set(x, y, z, underside(x, y, z, 6, 12))
                    if (spread > 0.55) {
                        for ((ax, az) in ARROWS) set(x + ax, y, z + az, underside(x + ax, y, z + az, 6, 12))
                    }
                }
            }
        }

        /**
         * Lower ledges just outside the rim (MAP-04): in the stretches where the shore noise is high a 2-4 block wide
         * shelf of grass, soil and rock hangs three blocks under the top layer, giving the island a terraced shoreline.
         */
        private fun shelves() {
            for (isl in layout.islands) {
                if (isl.radius < 14) continue
                val table = rims[isl.id] ?: continue
                val salt = (isl.id.hashCode() and 0xffff) + 41
                val ext = ceil(isl.radius * 1.24).toInt() + 5
                for (dx in -ext..ext) {
                    for (dz in -ext..ext) {
                        val d = hypot(dx.toDouble(), dz.toDouble())
                        val theta = atan2(dz.toDouble(), dx.toDouble())
                        val rim = rimAt(table, theta)
                        if (d <= rim) continue
                        val sector = noise2(cos(theta) * 2.4 + salt, sin(theta) * 2.4 - salt, salt)
                        if (sector < 0.1) continue
                        val width = 1.5 + 2.5 * sector.coerceIn(0.0, 1.0)
                        if (d > rim + width) continue
                        val x = isl.x + dx
                        val z = isl.z + dz
                        if (topCells.containsKey(key(x, z))) continue
                        if ((s - 2..s + 3).any { get(x, it, z) != null }) continue
                        val outward = (d - rim) / width
                        set(x, s - 3, z, material("ground"))
                        set(x, s - 4, z, material("soil"))
                        if (outward < 0.7) set(x, s - 5, z, material("rock"))
                        if (outward < 0.35) set(x, s - 6, z, material("rock"))
                    }
                }
            }
        }

        private fun topBlock(isl: ArenaIsland, x: Int, z: Int, d: Double, rim: Double): String = when (isl.kind) {
            ArenaIslandKind.CENTER -> when {
                d <= max(2.2, isl.radius * 0.14) -> "minecraft:smooth_quartz"
                d <= max(3.4, isl.radius * 0.14 + 1.2) -> material("deck")
                d >= rim - 1.0 -> material("rock")
                else -> if (rnd(x, z, 3) < 0.15) material("rock") else material("wall")
            }
            else -> material("ground")
        }

        private fun shallow(isl: ArenaIsland): String = material(if (isl.kind == ArenaIslandKind.CENTER) "wall" else "soil")

        private fun underside(x: Int, y: Int, z: Int, dy: Int, depth: Int): String {
            val r = rnd(x, y, z + 7)
            return when {
                r < 0.02 -> "minecraft:amethyst_block"
                dy in 3..5 && r < 0.10 -> material("ground")
                r in 0.45..<0.47 && dy > depth / 2 -> "minecraft:calcite"
                else -> material("rock")
            }
        }

        // ── bridges ──────────────────────────────────────────────────────

        private fun bridge(b: ArenaBridge) {
            val a = layout.islands.first { it.id == b.from }
            val c = layout.islands.first { it.id == b.to }
            val ax = a.x.toDouble()
            val az = a.z.toDouble()
            val dx = c.x - ax
            val dz = c.z - az
            val len = hypot(dx, dz)
            if (len < 1.0) return
            val half = b.width / 2.0 + 0.01
            val pad = ceil(half).toInt() + 1
            for (x in min(a.x, c.x) - pad..max(a.x, c.x) + pad) {
                for (z in min(a.z, c.z) - pad..max(a.z, c.z) + pad) {
                    if (topCells.containsKey(key(x, z))) continue
                    val t = (((x - ax) * dx + (z - az) * dz) / (len * len)).coerceIn(0.0, 1.0)
                    val dist = hypot(x - (ax + t * dx), z - (az + t * dz))
                    if (dist > half) continue
                    val spine = dist < 0.5
                    set(x, s, z, if (spine) material("wall") else material("deck"))
                    if (spine) set(x, s - 1, z, material("wall"))
                }
            }
            // Lantern posts every 8 blocks stand on small ledges just outside the deck, so the whole width stays
            // walkable (a post on the deck narrows a 3-wide bridge to 1 block and snags anyone walking along its edge).
            for (x in min(a.x, c.x) - pad..max(a.x, c.x) + pad) {
                for (z in min(a.z, c.z) - pad..max(a.z, c.z) + pad) {
                    if (topCells.containsKey(key(x, z)) || get(x, s, z) != null) continue
                    val t = (((x - ax) * dx + (z - az) * dz) / (len * len)).coerceIn(0.0, 1.0)
                    val dist = hypot(x - (ax + t * dx), z - (az + t * dz))
                    if (dist <= half || dist > half + 1.0) continue
                    val along = (t * len).roundToInt()
                    if (along in 4..(len.roundToInt() - 4) && along.mod(6) == 3) {
                        set(x, s, z, material("deck"))
                        set(x, s + 1, z, "minecraft:stone_brick_wall")
                        set(x, s + 2, z, material("light"))
                        // A lantern under the ledge lights the span from below, where the other base looks up at it.
                        set(x, s - 1, z, "minecraft:lantern[hanging=true]")
                    }
                }
            }
        }

        // ── team identity ────────────────────────────────────────────────

        /** The outermost ring of every base island in the team colour. */
        private fun rimTrim() {
            for ((k, isl) in topCells) {
                if (isl.kind != ArenaIslandKind.BASE || isl.team == null) continue
                val x = (k shr 32).toInt()
                val z = k.toInt()
                val edge = ARROWS.any { (ax, az) -> !topCells.containsKey(key(x + ax, z + az)) }
                if (edge) set(x, s, z, "minecraft:${isl.colour}_concrete")
            }
        }

        private fun teamPad(isl: ArenaIsland) {
            val spawns = stage.spawnPoints.filter { it.team == isl.team && nearestIsland(it.position)?.id == isl.id }
            if (spawns.isEmpty()) return
            val px = floor(spawns.sumOf { it.position.x } / spawns.size).toInt()
            val pz = floor(spawns.sumOf { it.position.z } / spawns.size).toInt()
            val colour = isl.colour
            keepClear(px, pz, 8)
            for (dx in -5..5) {
                for (dz in -5..5) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    val x = px + dx
                    val z = pz + dz
                    if (topCells[key(x, z)]?.id != isl.id) continue
                    when {
                        d <= 3.2 -> set(x, s, z, "minecraft:${colour}_terracotta")
                        d <= 4.4 -> set(x, s, z, "minecraft:${colour}_concrete")
                    }
                }
            }
            // Two banner posts flanking the pad, 6 blocks to either side of the island-centre -> pad axis and a few
            // blocks further out; kept off the straight lines through the spawns, so they never stand in a line of fire.
            var ox = (px - isl.x).toDouble()
            var oz = (pz - isl.z).toDouble()
            val n = hypot(ox, oz)
            if (n < 0.5) {
                ox = 0.0
                oz = 1.0
            } else {
                ox /= n
                oz /= n
            }
            for (sign in intArrayOf(-1, 1)) {
                val spot = (3 downTo 0).firstNotNullOfOrNull { out ->
                    val bx = px + (ox * out).roundToInt() + (-oz * 6 * sign).roundToInt()
                    val bz = pz + (oz * out).roundToInt() + (ox * 6 * sign).roundToInt()
                    (bx to bz).takeIf { topCells[key(bx, bz)]?.id == isl.id }
                } ?: continue
                val (bx, bz) = spot
                set(bx, s, bz, "minecraft:${colour}_concrete")
                set(bx, s + 1, bz, "minecraft:polished_blackstone_bricks")
                set(bx, s + 2, bz, "minecraft:polished_blackstone_bricks")
                set(bx, s + 3, bz, "minecraft:${colour}_banner[rotation=0]")
                reserve(bx, bz, 1)
            }
        }

        private fun nearestIsland(p: Vec3): ArenaIsland? =
            layout.islands.minByOrNull { (it.x - p.x) * (it.x - p.x) + (it.z - p.z) * (it.z - p.z) }

        private fun colourOfTeam(team: String): String =
            layout.islands.firstOrNull { it.team == TeamId(team) }?.colour ?: "white"

        // ── objective anchors ────────────────────────────────────────────

        private fun anchors() {
            for (region in stage.regions) {
                val name = region.name ?: continue
                val cx = floor((region.min.x + region.max.x) / 2).toInt()
                val cz = floor((region.min.z + region.max.z) / 2).toInt()
                val y = floor(region.min.y).toInt()
                when {
                    name.startsWith("core-") -> {
                        expectY(name, y, s + 1)
                        val colour = colourOfTeam(name.removePrefix("core-"))
                        pedestal(name, cx, cz, "minecraft:polished_blackstone", "minecraft:polished_blackstone_bricks", false)
                        for ((ax, az) in CORNERS) paintTop(cx + ax, cz + az, "minecraft:${colour}_concrete")
                        if (layout.structures.isEmpty()) keep(cx, cz, colour)
                    }
                    name.startsWith("jewelry-") -> {
                        expectY(name, y, s)
                        pedestal(name, cx, cz, "minecraft:smooth_quartz", "minecraft:chiseled_quartz_block", true)
                    }
                    name.startsWith("wool-") -> {
                        expectY(name, y, s)
                        pedestal(name, cx, cz, "minecraft:smooth_stone", "minecraft:chiseled_stone_bricks", true)
                    }
                    name == "tnt-spawn" -> {
                        expectY(name, y, s)
                        pedestal(name, cx, cz, "minecraft:black_concrete", "minecraft:obsidian", true)
                    }
                    name.startsWith("mob-spawn-") -> keepClear(cx, cz, 5)
                }
            }
            // Pillars sit one block above the surface; each gets a 3x3 footing in its team colour.
            for (pillar in stage.pillars) {
                val px = floor(pillar.position.x).toInt()
                val pz = floor(pillar.position.z).toInt()
                val py = floor(pillar.position.y).toInt()
                val label = "pillar ${pillar.team.value}/${pillar.tier.ordinalValue}"
                expectY(label, py, s + 1)
                for (dx in -1..1) for (dz in -1..1) paintTop(px + dx, pz + dz, "minecraft:polished_blackstone_bricks")
                reserve(px, pz, 2)
                keepClear(px, pz, 5)
            }
            for (pillar in stage.pillars) {
                val px = floor(pillar.position.x).toInt()
                val pz = floor(pillar.position.z).toInt()
                val centre = "minecraft:${colourOfTeam(pillar.team.value)}_concrete"
                if (!paintTop(px, pz, centre)) problems += "pillar ${pillar.team.value}/${pillar.tier.ordinalValue} at ($px, $pz) is off the islands"
            }
        }

        private fun expectY(what: String, actual: Int, expected: Int) {
            if (actual != expected) problems += "$what is at y=$actual, expected y=$expected (surface_y=$s)"
        }

        /** 3x3 plate at the surface layer: [ring] around a [centre] block; the centre cell must lie on an island. */
        private fun pedestal(what: String, cx: Int, cz: Int, ring: String, centre: String, volatileCentre: Boolean) {
            if (!topCells.containsKey(key(cx, cz))) {
                problems += "$what centre ($cx, $cz) is off the islands"
                return
            }
            for (dx in -1..1) for (dz in -1..1) paintTop(cx + dx, cz + dz, ring)
            set(cx, s, cz, centre)
            if (volatileCentre) volatile += ArenaBlockPos(cx, s, cz)
            reserve(cx, cz, 2)
            keepClear(cx, cz, 5)
        }

        private fun landmarks() {
            for (mark in layout.landmarks) {
                when (mark.kind) {
                    ArenaLandmarkKind.GENERATOR -> {
                        if (!topCells.containsKey(key(mark.x, mark.z))) {
                            problems += "generator landmark (${mark.x}, ${mark.z}) is off the islands"
                            continue
                        }
                        for (dx in -1..1) for (dz in -1..1) paintTop(mark.x + dx, mark.z + dz, "minecraft:polished_blackstone_bricks")
                        set(mark.x, s, mark.z, "minecraft:iron_block")
                        reserve(mark.x, mark.z, 2)
                        keepClear(mark.x, mark.z, 4)
                    }
                }
            }
        }

        /** Authored full-cube parts, before random features, which must leave their footprint alone. */
        private fun structures() {
            for (placement in layout.structures) {
                val part = requireNotNull(parts[placement.part]) { "missing structure '${placement.part}'" }
                val colours = palette + placement.palette
                for (box in part.boxes) {
                    val state = if (box.block.startsWith("@")) colours.getValue(box.block.drop(1)) else box.block
                    for (x in box.min.x..box.max.x) for (z in box.min.z..box.max.z) {
                        for (y in box.min.y..box.max.y) {
                            val pos = placement.transform(ArenaBlockPos(x, y, z), s)
                            if (state == "minecraft:air") blocks.remove(pos) else set(pos.x, pos.y, pos.z, state)
                            occupied += key(pos.x, pos.z)
                            reserved += key(pos.x, pos.z)
                        }
                    }
                }
            }
        }

        // ── consistency ──────────────────────────────────────────────────

        private fun checkSpawns() {
            for (spawn in stage.spawnPoints) {
                if (spawn.role == SpawnRole.SPECTATOR) continue
                val x = floor(spawn.position.x).toInt()
                val z = floor(spawn.position.z).toInt()
                val y = floor(spawn.position.y).toInt()
                val label = "spawn ${spawn.team?.value ?: spawn.role.name.lowercase()} ($x, $y, $z)"
                if (y != s + 1) problems += "$label stands at y=$y, expected y=${s + 1}"
                if (!topCells.containsKey(key(x, z))) {
                    problems += "$label is off the islands"
                    continue
                }
                reserve(x, z, 2)
            }
            for (isl in layout.islands) reserve(isl.x, isl.z, if (isl.kind == ArenaIslandKind.CENTER) 3 else 0)
            for (isl in layout.islands) if (isl.kind == ArenaIslandKind.CENTER) keepClear(isl.x, isl.z, max(5, (isl.radius * 0.18).toInt()))
        }

        private fun reserve(cx: Int, cz: Int, radius: Int) {
            for (dx in -radius..radius) for (dz in -radius..radius) reserved += key(cx + dx, cz + dz)
        }

        private fun keepClear(cx: Int, cz: Int, radius: Int) {
            for (dx in -radius..radius) for (dz in -radius..radius) clear += key(cx + dx, cz + dz)
        }

        private fun isFree(x: Int, z: Int): Boolean = key(x, z).let { it !in clear && it !in occupied }

        /**
         * A defended core: a low wall arc behind it (away from the arena centre), two flanking posts in front in the
         * team colour. The front stays open so attackers can reach the block.
         */
        private fun keep(cx: Int, cz: Int, colour: String) {
            val isl = nearestIsland(Vec3(cx + 0.5, 0.0, cz + 0.5)) ?: return
            val hub = layout.islands.firstOrNull { it.kind == ArenaIslandKind.CENTER }
            var ox = (cx - (hub?.x ?: isl.x)).toDouble()
            var oz = (cz - (hub?.z ?: isl.z)).toDouble()
            val n = hypot(ox, oz)
            if (n < 0.5) return
            ox /= n
            oz /= n
            fun onIsland(x: Int, z: Int) = topCells[key(x, z)]?.id == isl.id
            for (dx in -4..4) {
                for (dz in -4..4) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d < 2.6 || d > 3.7 || (dx * ox + dz * oz) / d < 0.5) continue
                    val x = cx + dx
                    val z = cz + dz
                    if (!onIsland(x, z)) continue
                    val high = if (rnd(x, z, 11) < 0.35) 3 else 2
                    for (k in 1..high) set(x, s + k, z, if (rnd(x, z + k, 12) < 0.2) "minecraft:cracked_polished_blackstone_bricks" else "minecraft:polished_blackstone_bricks")
                    occupied += key(x, z)
                }
            }
            for (sign in intArrayOf(-1, 1)) {
                val x = cx + (-ox * 3 + -oz * 3 * sign).roundToInt()
                val z = cz + (-oz * 3 + ox * 3 * sign).roundToInt()
                if (!onIsland(x, z)) continue
                for (k in 1..2) set(x, s + k, z, "minecraft:polished_blackstone_bricks")
                set(x, s + 3, z, "minecraft:${colour}_concrete")
                occupied += key(x, z)
            }
        }

        /** Keeps a straight lane from every island centre towards each bridge partner free of terrain features. */
        private fun lanes() {
            for (b in layout.bridges) {
                val a = layout.islands.first { it.id == b.from }
                val c = layout.islands.first { it.id == b.to }
                laneFrom(a, c, b.width)
                laneFrom(c, a, b.width)
            }
        }

        private fun laneFrom(from: ArenaIsland, to: ArenaIsland, width: Int) {
            val dx = (to.x - from.x).toDouble()
            val dz = (to.z - from.z).toDouble()
            val len = hypot(dx, dz)
            if (len < 1.0) return
            val ux = dx / len
            val uz = dz / len
            val half = width / 2 + 2
            val reach = (from.radius * 1.3).toInt()
            for (t in 0..reach) {
                for (w in -half..half) {
                    val x = (from.x + ux * t - uz * w).roundToInt()
                    val z = (from.z + uz * t + ux * w).roundToInt()
                    val k = key(x, z)
                    clear += k
                    laneCells += k
                    if (abs(w) <= width / 2 && k !in reserved) paintTop(x, z, material("wall"))
                }
            }
        }

        // ── silhouettes (MAP-05) ─────────────────────────────────────────

        /**
         * Landmarks that read from the other base: a tall themed spire with a team-coloured beacon beam on every large
         * base and on the centre, a lit cairn on large outposts, team banner standards around base rims, and glowing
         * pylons around objective pedestals. All are placed on free ground, so spawns, lanes, anchors and authored
         * parts never move.
         */
        private fun silhouettes() {
            val hub = layout.islands.firstOrNull { it.kind == ArenaIslandKind.CENTER }
            val hx = hub?.x?.toDouble() ?: (layout.islands.sumOf { it.x }.toDouble() / layout.islands.size)
            val hz = hub?.z?.toDouble() ?: (layout.islands.sumOf { it.z }.toDouble() / layout.islands.size)
            for (isl in layout.islands) {
                if (isl.radius < 18) continue
                when (isl.kind) {
                    ArenaIslandKind.BASE -> if (isl.radius >= 30) {
                        findSpot(isl, hx, hz, 3)?.let { (x, z) -> spire(x, z, 34, isl.colour) }
                        standards(isl)
                    }
                    ArenaIslandKind.CENTER -> if (isl.radius >= 30) findSpot(isl, hx, hz, 3)?.let { (x, z) -> spire(x, z, 42, "white") }
                    ArenaIslandKind.OUTPOST -> findSpot(isl, hx, hz, 2)?.let { (x, z) -> cairn(x, z) }
                }
            }
            pylons()
        }

        private fun footprintFree(isl: ArenaIsland, cx: Int, cz: Int, half: Int): Boolean {
            for (dx in -half..half) {
                for (dz in -half..half) {
                    val k = key(cx + dx, cz + dz)
                    if (topCells[k]?.id != isl.id || k in clear || k in occupied || k in reserved || k in laneCells) return false
                    if (get(cx + dx, s + 1, cz + dz) != null) return false
                }
            }
            return true
        }

        /** The free square of half-width [half] nearest to "behind the island, away from the arena hub"; `null` when none. */
        private fun findSpot(isl: ArenaIsland, hx: Double, hz: Double, half: Int): Pair<Int, Int>? {
            val base = if (isl.kind == ArenaIslandKind.CENTER) rnd(isl.x, isl.z, 90) * 2 * PI else atan2(isl.z - hz, isl.x - hx)
            for (f in SPOT_FRACTIONS) {
                for (off in SPOT_OFFSETS) {
                    val a = base + off
                    val cx = isl.x + (cos(a) * isl.radius * f).roundToInt()
                    val cz = isl.z + (sin(a) * isl.radius * f).roundToInt()
                    if (!footprintFree(isl, cx, cz, half)) continue
                    reserve(cx, cz, half + 1)
                    keepClear(cx, cz, half + 1)
                    take(cx, cz, half + 1)
                    return cx to cz
                }
            }
            return null
        }

        private fun fill(cx: Int, cz: Int, half: Int, y: Int, block: String) {
            for (dx in -half..half) for (dz in -half..half) set(cx + dx, y, cz + dz, block)
        }

        /** Half-width of the spire at height [rel] above the surface; the profile is what differs per theme. */
        private fun spireHalf(rel: Int, height: Int): Int = when (layout.theme) {
            ArenaTheme.TEMPERATE -> if (rel < 8) 2 else 1
            ArenaTheme.FROST -> if (rel < height * 0.22) 3 else if (rel < height * 0.5) 2 else 1
            ArenaTheme.DESERT -> if (rel < 6) 4 else if (rel < 12) 3 else if (rel < 18) 2 else 1
            ArenaTheme.VOLCANIC -> if (rel < 10) 2 else 1
        }

        /** A themed tower topped by a beacon: the stained glass above it colours the beam in [colour]. */
        private fun spire(cx: Int, cz: Int, height: Int, colour: String) {
            val band = "minecraft:${colour}_concrete"
            val wall = material("wall")
            val light = material("light")
            val top = s + height
            fill(cx, cz, 3, s + 1, material("rock"))
            fill(cx, cz, 2, s + 2, wall)
            for ((ax, az) in CORNERS) set(cx + 3 * ax, s + 1, cz + 3 * az, band)
            for (y in s + 3 until top) {
                val rel = y - s
                val half = spireHalf(rel, height)
                fill(cx, cz, half, y, if (rel % 9 == 0) band else wall)
                if (half == 1 && rel % 9 == 4) for ((ax, az) in ARROWS) set(cx + ax, y, cz + az, light)
            }
            if (layout.theme == ArenaTheme.VOLCANIC) {
                // Jagged horns rise at the corners of the flared foot.
                for ((i, c) in CORNERS.withIndex()) {
                    val horn = 7 + (i * 5 + rnd(cx, cz, 91 + i) * 6).toInt()
                    for (k in 3..horn) set(cx + 2 * c.first, s + k, cz + 2 * c.second, material("rock"))
                    set(cx + 2 * c.first, s + horn + 1, cz + 2 * c.second, light)
                }
            }
            fill(cx, cz, 2, top - 1, material("deck"))
            fill(cx, cz, 1, top, "minecraft:iron_block")
            set(cx, top + 1, cz, "minecraft:beacon")
            set(cx, top + 2, cz, "minecraft:${colour}_stained_glass")
            for (y in top - 4..top - 2) {
                set(cx + 2, y, cz, "minecraft:${colour}_wall_banner[facing=east]")
                set(cx - 2, y, cz, "minecraft:${colour}_wall_banner[facing=west]")
                set(cx, y, cz + 2, "minecraft:${colour}_wall_banner[facing=south]")
                set(cx, y, cz - 2, "minecraft:${colour}_wall_banner[facing=north]")
            }
        }

        /** A small lit marker column for outposts: visible across the gap, but not a beam. */
        private fun cairn(cx: Int, cz: Int) {
            fill(cx, cz, 1, s + 1, material("rock"))
            for (y in s + 2..s + 8) set(cx, y, cz, if (y % 3 == 0) material("wall") else material("deck"))
            set(cx, s + 9, cz, material("light"))
            for ((ax, az) in ARROWS) set(cx + ax, s + 9, cz + az, material("light"))
            set(cx, s + 10, cz, material("light"))
        }

        /** Tall team-banner standards spaced around the rim of a base island, far enough in not to hang over the edge. */
        private fun standards(isl: ArenaIsland) {
            val table = rims[isl.id] ?: return
            val colour = isl.colour
            val count = 8
            val phase = rnd(isl.x, isl.z, 92) * 2 * PI
            for (i in 0 until count) {
                val a = phase + i * 2 * PI / count
                val rr = rimAt(table, atan2(sin(a), cos(a))) - 4.0
                val x = isl.x + (cos(a) * rr).roundToInt()
                val z = isl.z + (sin(a) * rr).roundToInt()
                val k = key(x, z)
                if (topCells[k]?.id != isl.id || k in clear || k in occupied || k in reserved || k in laneCells) continue
                if (get(x, s + 1, z) != null) continue
                set(x, s, z, "minecraft:${colour}_concrete")
                for (y in s + 1..s + 6) set(x, y, z, "minecraft:polished_blackstone_bricks")
                set(x, s + 7, z, "minecraft:${colour}_banner[rotation=${(rnd(x, z, 93) * 16).toInt().coerceIn(0, 15)}]")
                take(x, z, 1)
                reserve(x, z, 1)
            }
        }

        /** Four glowing pylons around every objective pedestal so the spot reads as a destination, day or night. */
        private fun pylons() {
            for (region in stage.regions) {
                val name = region.name ?: continue
                if (!(name.startsWith("core-") || name.startsWith("jewelry-") || name.startsWith("wool-") || name == "tnt-spawn")) continue
                val cx = floor((region.min.x + region.max.x) / 2).toInt()
                val cz = floor((region.min.z + region.max.z) / 2).toInt()
                for ((ax, az) in CORNERS) {
                    val x = cx + 3 * ax
                    val z = cz + 3 * az
                    val k = key(x, z)
                    if (!topCells.containsKey(k) || k in occupied || k in reserved || k in laneCells) continue
                    if (get(x, s + 1, z) != null) continue
                    set(x, s + 1, z, "minecraft:polished_blackstone_bricks")
                    set(x, s + 2, z, "minecraft:polished_blackstone_bricks")
                    set(x, s + 3, z, material("light"))
                    occupied += k
                }
            }
        }

        // ── terrain features ─────────────────────────────────────────────

        /**
         * Grass under an opaque block turns to dirt on a random tick, which would change the surface of a freshly
         * built arena (and make the build look damaged). Build it as dirt from the start.
         */
        private fun settleGrass() {
            val covered = blocks.entries.filter { (pos, state) ->
                pos.y == s + 1 && !ArenaBuildPlan.isPassable(state) && !state.contains("_leaves")
            }.map { it.key }
            for (pos in covered) if (get(pos.x, s, pos.z) == "minecraft:grass_block") set(pos.x, s, pos.z, "minecraft:dirt")
        }

        private fun features() {
            for (isl in layout.islands) {
                val rng = Random(layout.seed xor (isl.id.hashCode().toLong() shl 9) xor 0x5eed5eedL)
                val r = isl.radius
                val center = isl.kind == ArenaIslandKind.CENTER
                repeat(if (center) 3 + (r / 7).toInt() else 1 + (r / 9).toInt()) { mound(isl, rng, center) }
                repeat((r * r / if (center) 45 else 60).toInt()) { cover(isl, rng, center) }
                if (!center) repeat((r * r / 120).toInt()) { tree(isl, rng) }
            }
        }

        /** A random free spot whose [reach]-square lies wholly on [isl]; `null` after [attempts] misses. */
        private fun pick(isl: ArenaIsland, rng: Random, tMin: Double, tMax: Double, reach: Int, attempts: Int = 30): Pair<Int, Int>? {
            repeat(attempts) {
                val ang = rng.nextDouble() * 2 * PI
                val t = tMin + rng.nextDouble() * (tMax - tMin)
                val cx = isl.x + (cos(ang) * isl.radius * t).roundToInt()
                val cz = isl.z + (sin(ang) * isl.radius * t).roundToInt()
                var ok = true
                loop@ for (dx in -reach..reach) {
                    for (dz in -reach..reach) {
                        if (topCells[key(cx + dx, cz + dz)]?.id != isl.id || !isFree(cx + dx, cz + dz)) {
                            ok = false
                            break@loop
                        }
                    }
                }
                if (ok) return cx to cz
            }
            return null
        }

        private fun take(cx: Int, cz: Int, reach: Int) {
            for (dx in -reach..reach) for (dz in -reach..reach) occupied += key(cx + dx, cz + dz)
        }

        /** A stepped hill: terraces one block high, so elevation is climbed in jumps. */
        private fun mound(isl: ArenaIsland, rng: Random, center: Boolean) {
            val mr = if (center) 4 + rng.nextInt(4) else 3 + rng.nextInt(4)
            val h = (if (center) 2 + rng.nextInt(3) else 2 + rng.nextInt(2)).coerceAtMost(mr - 1)
            val (cx, cz) = pick(isl, rng, 0.25, 0.7, mr + 1) ?: return
            for (dx in -mr..mr) {
                for (dz in -mr..mr) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d > mr) continue
                    val level = ceil((mr - d) / mr * h).toInt().coerceAtMost(h)
                    for (k in 1..level) {
                        val x = cx + dx
                        val z = cz + dz
                        val top = k == level
                        set(
                            x, s + k, z,
                            if (center) material("wall")
                            else if (top) material("ground") else material("soil"),
                        )
                    }
                }
            }
            take(cx, cz, mr + 1)
        }

        /** Walls and pillars (plaza ruins) on the centre island, boulders elsewhere. */
        private fun cover(isl: ArenaIsland, rng: Random, center: Boolean) {
            if (center) {
                if (rng.nextDouble() < 0.3) {
                    val (cx, cz) = pick(isl, rng, 0.2, 0.75, 2) ?: return
                    for (k in 1..3) set(cx, s + k, cz, material("wall"))
                    set(cx, s + 4, cz, material("wall"))
                    take(cx, cz, 1)
                    return
                }
                val length = 3 + rng.nextInt(4)
                val alongX = rng.nextBoolean()
                val (cx, cz) = pick(isl, rng, 0.2, 0.75, length / 2 + 2) ?: return
                for (i in -length / 2..length / 2) {
                    if (rng.nextDouble() < 0.15) continue
                    val x = if (alongX) cx + i else cx
                    val z = if (alongX) cz else cz + i
                    val high = if (rng.nextDouble() < 0.3) 3 else 2
                    for (k in 1..high) set(x, s + k, z, material("wall"))
                }
                take(cx, cz, length / 2 + 1)
            } else {
                val (cx, cz) = pick(isl, rng, 0.25, 0.75, 3) ?: return
                val w = 2 + rng.nextInt(2)
                val d = 2 + rng.nextInt(2)
                for (dx in 0 until w) {
                    for (dz in 0 until d) {
                        val high = if (rng.nextDouble() < 0.4) 2 else 1
                        for (k in 1..high) {
                            set(cx + dx, s + k, cz + dz, material("rock"))
                        }
                    }
                }
                take(cx, cz, 2)
            }
        }

        private fun tree(isl: ArenaIsland, rng: Random) {
            val (cx, cz) = pick(isl, rng, 0.2, 0.68, 3) ?: return
            val log = material("log")
            val foliage = material("leaves")
            val leaves = if (foliage.endsWith("_leaves")) "$foliage[persistent=true]" else foliage
            val trunk = 4 + rng.nextInt(3)
            for (k in 1..trunk) set(cx, s + k, cz, log)
            for (layer in 0..3) {
                val y = s + trunk - 1 + layer
                val reach = if (layer < 2) 2 else 1
                for (dx in -reach..reach) {
                    for (dz in -reach..reach) {
                        if (layer < 2 && abs(dx) == 2 && abs(dz) == 2) continue
                        if (layer == 3 && abs(dx) + abs(dz) > 1) continue
                        if (dx == 0 && dz == 0 && layer < 2) continue
                        if (get(cx + dx, y, cz + dz) == null) set(cx + dx, y, cz + dz, leaves)
                    }
                }
            }
            set(cx, s + trunk + 2, cz, leaves)
            take(cx, cz, 3)
        }

        // ── decoration ───────────────────────────────────────────────────

        private fun deco() {
            if (layout.theme != ArenaTheme.TEMPERATE) return
            for ((k, isl) in topCells) {
                if (isl.kind == ArenaIslandKind.CENTER || k in reserved) continue
                val x = (k shr 32).toInt()
                val z = k.toInt()
                if (get(x, s, z) != material("ground") || get(x, s + 1, z) != null) continue
                val r = rnd(x, z, 5)
                if (r >= 0.09) continue
                set(x, s + 1, z, FLOWERS[(r / 0.09 * FLOWERS.size).toInt().coerceAtMost(FLOWERS.size - 1)])
            }
        }
    }

    private const val RIM_STEPS: Int = 720
    private val SPOT_FRACTIONS: List<Double> = listOf(0.55, 0.45, 0.65, 0.35, 0.72, 0.25)
    private val SPOT_OFFSETS: List<Double> = listOf(0.0, 0.3, -0.3, 0.6, -0.6, 0.9, -0.9, 1.3, -1.3, 1.8, -1.8, 2.4, -2.4, 3.1)
    private val ARROWS: List<Pair<Int, Int>> = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val CORNERS: List<Pair<Int, Int>> = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val FLOWERS: List<String> = listOf(
        "minecraft:short_grass", "minecraft:short_grass", "minecraft:short_grass", "minecraft:dandelion",
        "minecraft:poppy", "minecraft:cornflower", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
    )
}
