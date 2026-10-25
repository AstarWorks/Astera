package com.astarworks.astera.core.domain.stage

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.TeamId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
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
 * Per island: terrain, then bridges between islands (an island block always wins over a bridge block), a
 * team-coloured rim and spawn pad with two banner posts on base islands, a pedestal under every objective
 * anchor (`core-*`, `jewelry-*`, `wool-*`, `tnt-spawn`, pillar footings, generator landmarks), a defended wall arc
 * behind each core, terrain features (stepped mounds, plaza ruins / boulders as cover, trees) kept clear of spawn pads,
 * anchors and the lanes leading to bridges, and a sprinkling of flowers away from everything a player has to see.
 */
public object ArenaIslandGenerator {

    /** Builds the plan for a stage that has a [Stage.layout]. */
    public fun generate(stage: Stage): ArenaBuildPlan {
        val layout = requireNotNull(stage.layout) { "stage '${stage.id.value}' has no arena layout" }
        return Builder(stage, layout).build()
    }

    private class Builder(val stage: Stage, val layout: ArenaLayout) {
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
            rimTrim()
            layout.islands.filter { it.kind == ArenaIslandKind.BASE && it.team != null }.forEach { teamPad(it) }
            anchors()
            landmarks()
            checkSpawns()
            lanes()
            features()
            settleGrass()
            deco()
            return ArenaBuildPlan(blocks, volatile, s, problems)
        }

        // ── terrain ──────────────────────────────────────────────────────

        private fun island(isl: ArenaIsland) {
            val random = Random(layout.seed xor (isl.id.hashCode().toLong() shl 20))
            val p1 = random.nextDouble() * 2 * PI
            val p2 = random.nextDouble() * 2 * PI
            val p3 = random.nextDouble() * 2 * PI
            val r = isl.radius
            fun rimAt(theta: Double): Double =
                r * (1.0 + 0.09 * sin(2 * theta + p1) + 0.06 * sin(3 * theta + p2) + 0.04 * sin(5 * theta + p3))

            // The underside tapers to 0.8 x radius for small islands; big ones grow thinner relative to their width
            // (a 30-block island is ~15 deep, not 24), which keeps the build and the kill plane in reach.
            val fullDepth = r * 0.8
            val maxDepth = if (fullDepth <= 10.0) fullDepth else 10.0 + (fullDepth - 10.0) * 0.35
            val stalacticeMax = min(r * 0.4, 7.0)
            val ext = ceil(r * 1.25).toInt()
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
                    val depth = (maxDepth * (1 - t * t).pow(0.85)).roundToInt() + 2 + (rnd(x, z, 1) * 2.5).toInt()
                    for (dy in 0..depth) {
                        val state = when {
                            dy == 0 -> topBlock(isl, x, z, d, rim)
                            dy <= 2 -> shallow(isl, x, z, dy)
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
                while (get(x, bottom - 1, z) != null) bottom--
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

        private fun topBlock(isl: ArenaIsland, x: Int, z: Int, d: Double, rim: Double): String = when (isl.kind) {
            ArenaIslandKind.CENTER -> when {
                d <= max(2.2, isl.radius * 0.14) -> "minecraft:smooth_quartz"
                d <= max(3.4, isl.radius * 0.14 + 1.2) -> "minecraft:polished_andesite"
                d >= rim - 1.0 -> "minecraft:polished_deepslate"
                else -> {
                    val r = rnd(x, z, 3)
                    when {
                        r < 0.15 -> "minecraft:mossy_stone_bricks"
                        r < 0.30 -> "minecraft:cracked_stone_bricks"
                        else -> "minecraft:stone_bricks"
                    }
                }
            }
            else -> if (rnd(x, z, 3) < 0.08) "minecraft:moss_block" else "minecraft:grass_block"
        }

        private fun shallow(isl: ArenaIsland, x: Int, z: Int, dy: Int): String = when (isl.kind) {
            ArenaIslandKind.CENTER -> if (rnd(x, z + dy, 2) < 0.2) "minecraft:mossy_stone_bricks" else "minecraft:stone_bricks"
            else -> if (rnd(x, z + dy, 2) < 0.15) "minecraft:coarse_dirt" else "minecraft:dirt"
        }

        private fun underside(x: Int, y: Int, z: Int, dy: Int, depth: Int): String {
            val r = rnd(x, y, z + 7)
            return when {
                r < 0.02 -> "minecraft:amethyst_block"
                dy in 3..5 && r < 0.10 -> "minecraft:moss_block"
                r < 0.25 -> "minecraft:tuff"
                r < 0.45 -> "minecraft:andesite"
                r < 0.47 && dy > depth / 2 -> "minecraft:calcite"
                else -> "minecraft:stone"
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
                    set(x, s, z, if (spine) "minecraft:stone_bricks" else "minecraft:polished_andesite")
                    if (spine) set(x, s - 1, z, "minecraft:stone_bricks")
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
                    if (along in 4..(len.roundToInt() - 4) && along.mod(8) == 4) {
                        set(x, s, z, "minecraft:polished_andesite")
                        set(x, s + 1, z, "minecraft:stone_brick_wall")
                        set(x, s + 2, z, "minecraft:lantern[hanging=false]")
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
                        keep(cx, cz, colour)
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
                    clear += key((from.x + ux * t - uz * w).roundToInt(), (from.z + uz * t + ux * w).roundToInt())
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
                            if (center) CENTER_BRICKS[(rnd(x, z + k, 21) * CENTER_BRICKS.size).toInt()]
                            else if (top) "minecraft:grass_block" else "minecraft:dirt",
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
                    for (k in 1..3) set(cx, s + k, cz, CENTER_BRICKS[(rnd(cx, cz + k, 22) * CENTER_BRICKS.size).toInt()])
                    set(cx, s + 4, cz, "minecraft:chiseled_stone_bricks")
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
                    for (k in 1..high) set(x, s + k, z, CENTER_BRICKS[(rnd(x, z + k, 23) * CENTER_BRICKS.size).toInt()])
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
                            set(cx + dx, s + k, cz + dz, BOULDERS[(rnd(cx + dx, cz + dz + k, 24) * BOULDERS.size).toInt()])
                        }
                    }
                }
                take(cx, cz, 2)
            }
        }

        private fun tree(isl: ArenaIsland, rng: Random) {
            val (cx, cz) = pick(isl, rng, 0.2, 0.68, 3) ?: return
            val birch = rng.nextDouble() < 0.4
            val log = if (birch) "minecraft:birch_log" else "minecraft:oak_log"
            val leaves = if (birch) "minecraft:birch_leaves[persistent=true]" else "minecraft:oak_leaves[persistent=true]"
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
            for ((k, isl) in topCells) {
                if (isl.kind == ArenaIslandKind.CENTER || k in reserved) continue
                val x = (k shr 32).toInt()
                val z = k.toInt()
                if (get(x, s, z) != "minecraft:grass_block" || get(x, s + 1, z) != null) continue
                val r = rnd(x, z, 5)
                if (r >= 0.09) continue
                set(x, s + 1, z, FLOWERS[(r / 0.09 * FLOWERS.size).toInt().coerceAtMost(FLOWERS.size - 1)])
            }
        }
    }

    private val CENTER_BRICKS: List<String> = listOf(
        "minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks",
    )
    private val BOULDERS: List<String> = listOf(
        "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:andesite", "minecraft:stone",
    )
    private val ARROWS: List<Pair<Int, Int>> = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    private val CORNERS: List<Pair<Int, Int>> = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
    private val FLOWERS: List<String> = listOf(
        "minecraft:short_grass", "minecraft:short_grass", "minecraft:short_grass", "minecraft:dandelion",
        "minecraft:poppy", "minecraft:cornflower", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
    )
}
