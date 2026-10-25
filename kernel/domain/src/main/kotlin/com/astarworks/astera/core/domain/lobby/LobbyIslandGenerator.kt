package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.MessageKey
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural sky-island lobby (ADR-0180). A pure, deterministic function of
 * `(seed, centre, surfaceY)` returning a [LobbyBuildPlan]; no Bukkit, no I/O.
 *
 * Layout (offsets from the plaza/fountain centre, +x east, +z south):
 *  - main island r27 with the plaza (r10), fountain, benches, lamp posts, signposts,
 *    a stream that falls off the west rim, trees and flowers; spawn 6 blocks south of the fountain.
 *  - market island east (47, 0): shop and gear-keeper stalls.
 *  - queue island south (0, 50): one pad + sign board per queue lane ([LobbyLanePadLayout]), queue master.
 *  - garden island west (-44, 0): pond and a waterfall over the rim.
 *  - pavilion island north (0, -42): gazebo viewpoint.
 *  - three small floating rocks with cherry trees.
 *  - training island east of the market (F3-11, [LobbyTrainingLayout]): firing lanes, melee pit,
 *    moving-target track and the weapon rack, joined to the main island by a bridge.
 * Bridges (3 wide, fenced, lantern posts) join the four satellites to the main island.
 */
public object LobbyIslandGenerator {

    /**
     * [lanes] are the queue lane ids (from `content/matchmaking/queue.yaml`); one pad + sign
     * board is built per lane, placed by [LobbyLanePadLayout].
     */
    public fun generate(seed: Long, centerX: Int, centerZ: Int, surfaceY: Int, lanes: List<String>): LobbyBuildPlan =
        Builder(seed, centerX, centerZ, surfaceY, lanes).build()

    private class Island(
        val cx: Int,
        val cz: Int,
        val r: Double,
        val topY: Int,
        val p1: Double,
        val p2: Double,
        val p3: Double,
    ) {
        fun radiusAt(theta: Double): Double =
            r * (1.0 + 0.09 * sin(2 * theta + p1) + 0.06 * sin(3 * theta + p2) + 0.04 * sin(5 * theta + p3))
    }

    private enum class Stall { SHOP, GEAR }

    private class Builder(val seed: Long, val cx: Int, val cz: Int, val s: Int, val lanes: List<String>) {
        private val blocks = HashMap<LobbyBlockPos, String>(80_000)
        private val signs = ArrayList<LobbySign>()
        private val npcSpots = LinkedHashMap<String, LobbyNpcSpot>()
        private val lanePads = LinkedHashMap<String, Vec3>()
        private val rng = Random(seed)
        private val trees = ArrayList<Pair<Int, Int>>()

        fun set(x: Int, y: Int, z: Int, state: String) {
            blocks[LobbyBlockPos(x, y, z)] = state
        }

        fun get(x: Int, y: Int, z: Int): String? = blocks[LobbyBlockPos(x, y, z)]

        private fun hash(a: Int, b: Int, c: Int): Int {
            var h = seed.toInt() xor (seed ushr 32).toInt()
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

        fun build(): LobbyBuildPlan {
            val main = island(0, 0, 27.0, s)
            val market = island(47, 0, 13.0, s)
            val queue = island(0, LobbyLanePadLayout.islandOffsetZ(lanes.size), LobbyLanePadLayout.islandRadius(lanes.size), s)
            val garden = island(-44, 0, 11.0, s)
            val pavilion = island(0, -42, 10.0, s)
            val rocks = listOf(
                island(-31, -35, 4.5, s - 7),
                island(35, 38, 5.5, s + 4),
                island(-30, 40, 3.5, s - 11),
            )
            // The training island (F3-11) has its own random stream so the older islands keep their shape.
            island(
                LobbyTrainingLayout.ISLAND_X, LobbyTrainingLayout.ISLAND_Z, LobbyTrainingLayout.ISLAND_RADIUS, s,
                Random(seed xor TRAINING_SALT),
                depthScale = TRAINING_DEPTH_SCALE,
            )
            val allIslands = listOf(main, market, queue, garden, pavilion) + rocks

            paths()
            ringPath()
            plaza()
            bridge(15, 0, 40, 0)
            bridge(-15, 0, -36, 0)
            bridge(0, 15, 0, 38)
            bridge(0, -15, 0, -34)
            bridge(
                LobbyTrainingLayout.BRIDGE_FROM_X, LobbyTrainingLayout.ISLAND_Z,
                LobbyTrainingLayout.BRIDGE_TO_X, LobbyTrainingLayout.ISLAND_Z,
            )
            playGate()
            signposts()
            trainingIsland()
            marketIsland()
            queueIsland()
            gardenIsland(garden)
            pavilionIsland()
            streamAndWaterfall(main)
            plazaNpcs()

            allIslands.forEach { decorate(it) }
            return LobbyBuildPlan(
                blocks = blocks,
                signs = signs,
                spawn = Vec3(cx + 0.5, s + 1.0, cz + 6.5),
                spawnYaw = 180f,
                surfaceY = s,
                npcSpots = npcSpots,
                lanePads = lanePads,
                training = LobbyTrainingLayout.plan(cx, cz, s),
            )
        }

        // ── terrain ──────────────────────────────────────────────────────

        private fun island(ox: Int, oz: Int, r: Double, topY: Int, random: Random = rng, depthScale: Double = 1.0): Island {
            val isl = Island(
                cx + ox, cz + oz, r, topY,
                random.nextDouble() * 2 * PI, random.nextDouble() * 2 * PI, random.nextDouble() * 2 * PI,
            )
            val ext = ceil(r * 1.25).toInt()
            for (dx in -ext..ext) {
                for (dz in -ext..ext) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    val rim = isl.radiusAt(atan2(dz.toDouble(), dx.toDouble()))
                    if (d > rim) continue
                    val t = d / rim
                    val x = isl.cx + dx
                    val z = isl.cz + dz
                    val depth = (r * 0.9 * depthScale * (1 - t * t).pow(0.85)).roundToInt() + 2 + (rnd(x, z, 1) * 2.5).toInt()
                    for (dy in 0..depth) {
                        val state = when {
                            dy == 0 -> "minecraft:grass_block"
                            dy <= 2 -> if (rnd(x, z + dy, 2) < 0.15) "minecraft:coarse_dirt" else "minecraft:dirt"
                            else -> underside(x, topY - dy, z, dy, depth)
                        }
                        set(x, topY - dy, z, state)
                    }
                }
            }
            // stalactites
            val count = (r / 2.5).toInt() + 1
            repeat(count) {
                val ang = random.nextDouble() * 2 * PI
                val t = 0.1 + random.nextDouble() * 0.65
                val rim = isl.radiusAt(ang)
                val x = isl.cx + (cos(ang) * rim * t).roundToInt()
                val z = isl.cz + (sin(ang) * rim * t).roundToInt()
                var bottom = topY
                while (get(x, bottom - 1, z) != null) bottom--
                val len = 3 + (random.nextDouble() * r * 0.45 * depthScale).toInt()
                for (k in 1..len) {
                    val spread = (len - k).toDouble() / len
                    val y = bottom - k
                    set(x, y, z, underside(x, y, z, 6, 12))
                    if (spread > 0.55) {
                        for ((ax, az) in ARROWS) set(x + ax, y, z + az, underside(x + ax, y, z + az, 6, 12))
                    }
                }
            }
            return isl
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

        // ── main island: paths, plaza, fountain ─────────────────────────

        private fun paths() {
            // axis paths from plaza edge to each satellite centre; grass only (bridge handles the gaps)
            for ((dir, reach) in AXES) {
                for (k in 10..reach) {
                    for (l in -1..1) {
                        val x = cx + dir.first * k + dir.second * l
                        val z = cz + dir.second * k + dir.first * l
                        if (get(x, s, z) == "minecraft:grass_block") {
                            val edge = l != 0 && rnd(x, z, 3) < 0.3
                            set(x, s, z, if (edge) "minecraft:coarse_dirt" else "minecraft:dirt_path")
                        }
                    }
                }
            }
        }

        /** Ring walk around the plaza (r 14..15.6) with a lamp post every 45 degrees. */
        private fun ringPath() {
            for (dx in -17..17) {
                for (dz in -17..17) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d < 14.0 || d > 15.6) continue
                    val x = cx + dx
                    val z = cz + dz
                    if (get(x, s, z) == "minecraft:grass_block") {
                        set(x, s, z, if (rnd(x, z, 4) < 0.25) "minecraft:coarse_dirt" else "minecraft:dirt_path")
                    }
                }
            }
            for (k in 0 until 8) {
                val ang = PI / 8 + k * PI / 4
                val x = cx + (cos(ang) * 16.6).roundToInt()
                val z = cz + (sin(ang) * 16.6).roundToInt()
                if (get(x, s, z) == "minecraft:grass_block") lampPost(x, z)
            }
        }

        private fun plaza() {
            for (dx in -10..10) {
                for (dz in -10..10) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d > 10.0) continue
                    val state = when {
                        d >= 9.0 -> "minecraft:smooth_stone"
                        d >= 4.6 -> if ((dx + dz) and 1 == 0) "minecraft:stone_bricks" else "minecraft:polished_andesite"
                        else -> "minecraft:polished_andesite"
                    }
                    set(cx + dx, s, cz + dz, state)
                }
            }
            // fountain basin
            for (dx in -4..4) {
                for (dz in -4..4) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    val x = cx + dx
                    val z = cz + dz
                    when {
                        d < 2.6 -> set(x, s, z, "minecraft:water")
                        d < 3.6 -> {
                            set(x, s, z, "minecraft:stone_bricks")
                            set(x, s + 1, z, "minecraft:stone_brick_slab[type=bottom]")
                        }
                    }
                }
            }
            set(cx, s, cz, "minecraft:chiseled_stone_bricks")
            set(cx, s + 1, cz, WALL)
            set(cx, s + 2, cz, WALL)
            set(cx, s + 3, cz, "minecraft:water")
            for ((ax, az) in ARROWS) set(cx + ax, s + 3, cz + az, WALL)
            // spawn tile
            for (dx in -1..1) for (dz in -1..1) set(cx + dx, s, cz + 6 + dz, "minecraft:smooth_quartz")
            set(cx, s, cz + 6, "minecraft:chiseled_quartz_block")
            // benches (back away from the fountain), lamp posts
            for (sx in listOf(-1, 1)) {
                for (sz in listOf(-1, 1)) {
                    val facing = if (sz > 0) "south" else "north"
                    for (k in -1..1) {
                        set(cx + sx * 6 + k, s + 1, cz + sz * 6, "minecraft:spruce_stairs[facing=$facing,half=bottom,shape=straight]")
                    }
                    lampPost(cx + sx * 9, cz + sz * 4)
                    lampPost(cx + sx * 4, cz + sz * 9)
                }
            }
        }

        /**
         * The Play gate (ADR-0248): a lit quartz arch two blocks in front of the spawn tile, facing it. The Play
         * NPC stands under it; its hologram ("Play / プレイ") floats above the lintel. The one obvious place to start a game.
         */
        private fun playGate() {
            val gz = cz + PLAY_GATE_DZ
            for (side in intArrayOf(-2, 2)) {
                for (h in 1..4) set(cx + side, s + h, gz, "minecraft:quartz_pillar[axis=y]")
                set(cx + side, s + 5, gz, "minecraft:sea_lantern")
            }
            for (dx in -1..1) set(cx + dx, s + 5, gz, "minecraft:chiseled_quartz_block")
            for (dx in intArrayOf(-1, 1)) set(cx + dx, s + 4, gz, "minecraft:lantern[hanging=true]")
            // Glowing floor strip so the gate reads as a portal from the spawn tile.
            for (dx in -1..1) set(cx + dx, s, gz, "minecraft:smooth_quartz")
            npcSpots["play-npc"] = LobbyNpcSpot(cx + 0.5, s + 1.0, gz + 0.5, 0f)
        }

        private fun lampPost(x: Int, z: Int) {
            set(x, s + 1, z, WALL)
            set(x, s + 2, z, WALL)
            set(x, s + 3, z, WALL)
            set(x, s + 4, z, "minecraft:lantern[hanging=false]")
        }

        private fun signposts() {
            val specs = listOf(
                Triple(9, 2, "market") to 4,
                Triple(-9, -2, "garden") to 12,
                Triple(-2, -9, "pavilion") to 0,
                Triple(2, 9, "queue") to 8,
                Triple(9, -6, "training") to 4,
            )
            for ((spec, rotation) in specs) {
                val x = cx + spec.first
                val z = cz + spec.second
                set(x, s + 1, z, "minecraft:oak_sign[rotation=$rotation]")
                signs += LobbySign(
                    LobbyBlockPos(x, s + 1, z),
                    "minecraft:oak_sign[rotation=$rotation]",
                    listOf(
                        MessageKey("astera.lobby.sign.dir.${spec.third}.title"),
                        MessageKey("astera.lobby.sign.dir.${spec.third}.subtitle"),
                        null,
                        null,
                    ),
                )
            }
        }

        private fun plazaNpcs() {
            val cells = mapOf("travel-npc" to (-4 to -4), "party-npc" to (4 to -4), "profile-npc" to (-4 to 4), "settings-npc" to (4 to 4))
            for ((id, cell) in cells) {
                val (dx, dz) = cell
                val yaw = Math.toDegrees(atan2(dx.toDouble(), -dz.toDouble())).toFloat()
                npcSpots[id] = LobbyNpcSpot(cx + dx + 0.5, s + 1.0, cz + dz + 0.5, yaw + 0f)
            }
        }

        // ── bridges ──────────────────────────────────────────────────────

        private fun bridge(fromX: Int, fromZ: Int, toX: Int, toZ: Int) {
            val sx = Integer.signum(toX - fromX)
            val sz = Integer.signum(toZ - fromZ)
            val len = maxOf(kotlin.math.abs(toX - fromX), kotlin.math.abs(toZ - fromZ))
            val lx = sz // lateral axis
            val lz = sx
            val alongX = sx != 0
            val fence = if (alongX) "minecraft:oak_fence[east=true,west=true]" else "minecraft:oak_fence[north=true,south=true]"
            for (k in 0..len) {
                val x = cx + fromX + sx * k
                val z = cz + fromZ + sz * k
                if (get(x, s, z) != null) continue
                for (l in -1..1) {
                    val bx = x + lx * l
                    val bz = z + lz * l
                    set(bx, s, bz, if (l == 0) "minecraft:spruce_planks" else "minecraft:oak_planks")
                    if (k % 3 == 0) set(bx, s - 1, bz, "minecraft:dark_oak_planks")
                }
                for (l in listOf(-1, 1)) {
                    val bx = x + lx * l
                    val bz = z + lz * l
                    if (k % 5 == 2) {
                        set(bx, s + 1, bz, "minecraft:oak_fence")
                        set(bx, s + 2, bz, "minecraft:oak_fence")
                        set(bx, s + 3, bz, "minecraft:lantern[hanging=false]")
                    } else {
                        set(bx, s + 1, bz, fence)
                    }
                }
                if (k % 6 == 0) {
                    set(x, s - 1, z, "minecraft:dark_oak_planks")
                    set(x, s - 2, z, "minecraft:lantern[hanging=true]")
                }
            }
        }

        // ── market island ────────────────────────────────────────────────

        private fun marketIsland() {
            val mx = cx + 47
            val mz = cz
            for (dx in -10..10) {
                for (dz in -10..10) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d > 9.6 || get(mx + dx, s, mz + dz) == null) continue
                    val state = when {
                        d >= 8.6 -> "minecraft:smooth_stone"
                        (dx + dz) and 1 == 0 -> "minecraft:stone_bricks"
                        else -> "minecraft:polished_andesite"
                    }
                    set(mx + dx, s, mz + dz, state)
                }
            }
            lampPost(mx, mz)
            for ((px, pz) in listOf(-4 to -4, 4 to 4, -4 to 4, 4 to -4)) {
                set(mx + px, s + 1, mz + pz, "minecraft:oak_leaves[persistent=true]")
                set(mx + px, s, mz + pz, "minecraft:coarse_dirt")
            }
            stall(mx, mz - 6, 0, 1, Stall.SHOP, "shop-npc")
            stall(mx, mz + 6, 0, -1, Stall.GEAR, "gear-keeper-npc")
        }

        /** Market stall: NPC at (nx, nz), customers approach from direction (dx, dz). */
        private fun stall(nx: Int, nz: Int, dx: Int, dz: Int, kind: Stall, npcId: String) {
            val ax = -dz
            val az = dx
            fun w(u: Int, v: Int, y: Int, state: String) = set(nx + u * ax + v * dx, y, nz + u * az + v * dz, state)
            val face = when {
                dz == 1 -> "south"
                dz == -1 -> "north"
                dx == 1 -> "east"
                else -> "west"
            }
            val (roofA, roofB) = when (kind) {
                Stall.SHOP -> "minecraft:red_wool" to "minecraft:white_wool"
                Stall.GEAR -> "minecraft:blue_wool" to "minecraft:white_wool"
            }
            for (u in -3..3) {
                for (v in -2..2) w(u, v, s + 4, if (u and 1 == 0) roofA else roofB)
                // back wall
                val wall = when (kind) {
                    Stall.SHOP -> "minecraft:oak_planks"
                    Stall.GEAR -> "minecraft:spruce_planks"
                }
                for (y in 1..3) w(u, -2, s + y, if (u == -3 || u == 3) "minecraft:stripped_oak_log[axis=y]" else wall)
            }
            for (u in listOf(-3, 3)) {
                for (v in listOf(1)) for (y in 1..3) w(u, v, s + y, "minecraft:oak_fence")
            }
            for (u in -2..2) {
                w(u, 1, s + 1, counterBlock(kind, u, face))
                if (u == -2 || u == 2) w(u, 1, s + 2, "minecraft:lantern[hanging=false]")
                when (kind) {
                    Stall.SHOP -> w(u, -2, s + 2, if (u and 1 == 0) "minecraft:bookshelf" else "minecraft:oak_planks")
                    Stall.GEAR -> if (u == 0) w(u, -2, s + 1, "minecraft:cartography_table")
                }
            }
            npcSpots[npcId] = LobbyNpcSpot(
                nx + 0.5, s + 1.0, nz + 0.5,
                Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat() + 0f,
            )
        }

        private fun counterBlock(kind: Stall, u: Int, face: String): String = when (kind) {
            Stall.SHOP -> if (u == -2 || u == 2) "minecraft:barrel[facing=up]" else "minecraft:oak_planks"
            Stall.GEAR -> when (u) {
                -1, 1 -> "minecraft:loom[facing=$face]"
                0 -> "minecraft:spruce_planks"
                else -> "minecraft:barrel[facing=up]"
            }
        }

        // ── queue island ─────────────────────────────────────────────────

        private fun queueIsland() {
            val qx = cx
            val qz = cz + 50
            for (dx in -13..13) {
                for (dz in -13..13) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d > 12.0 || get(qx + dx, s, qz + dz) == null) continue
                    set(qx + dx, s, qz + dz, if (d >= 10.8) "minecraft:smooth_stone" else if ((dx + dz) and 1 == 0) "minecraft:stone_bricks" else "minecraft:polished_andesite")
                }
            }
            npcSpots["queue-npc"] = LobbyNpcSpot(qx + 2.5, s + 1.0, qz + 0.5, 180f)
            lampPost(qx - 3, qz - 3)
            lampPost(qx + 3, qz - 3)
            val colors = listOf("lime", "red", "light_blue", "orange", "magenta", "yellow", "cyan", "purple")
            LobbyLanePadLayout.slots(lanes.size).forEach { slot ->
                val lane = lanes[slot.index]
                val px = qx + slot.dx
                val pz = qz + slot.dz
                val color = colors[slot.index % colors.size]
                // The board stands behind the pad: south of it facing north, or north of it facing south.
                val bz = if (slot.boardFacesNorth) pz + 3 else pz - 3
                val sz = if (slot.boardFacesNorth) pz + 2 else pz - 2
                for (dx in -2..2) for (dz in -2..2) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    if (d <= 2.3) set(px + dx, s, pz + dz, if (d <= 1.0) "minecraft:sea_lantern" else if (d <= 1.9) "minecraft:${color}_concrete" else "minecraft:white_concrete")
                }
                for (u in -2..2) {
                    for (y in 1..4) set(px + u, y + s, bz, if (y == 3) "minecraft:${color}_concrete" else "minecraft:quartz_bricks")
                    set(px + u, s + 5, bz, "minecraft:quartz_slab[type=bottom]")
                }
                set(px - 2, s + 1, bz, "minecraft:sea_lantern")
                set(px + 2, s + 1, bz, "minecraft:sea_lantern")
                val signPos = LobbyBlockPos(px, s + 2, sz)
                val signState = "minecraft:oak_wall_sign[facing=${if (slot.boardFacesNorth) "north" else "south"}]"
                set(signPos.x, signPos.y, signPos.z, signState)
                signs += LobbySign(
                    signPos,
                    signState,
                    listOf(
                        MessageKey("astera.lobby.sign.lane.title"),
                        null,
                        null,
                        MessageKey("astera.lobby.sign.lane.hint"),
                    ),
                    placeholders = mapOf("lane" to LobbyLanePadLayout.displayName(lane)),
                )
                lanePads[lane] = Vec3(px + 0.5, s + 1.0, pz + 0.5)
            }
        }


        // ── training island (F3-11) ─────────────────────────────────────

        private fun trainingIsland() {
            val ix = cx + LobbyTrainingLayout.ISLAND_X
            val iz = cz + LobbyTrainingLayout.ISLAND_Z
            val shootX = ix + LobbyTrainingLayout.SHOOT_X
            val laneColors = listOf("lime", "yellow", "orange", "red")

            // firing platform, 5 wide, spanning every lane, with a yellow firing line on its east edge
            for (x in shootX - 3..shootX + 1) {
                for (z in iz - 13..iz + 3) {
                    val edge = x == shootX - 3 || z == iz - 13 || z == iz + 3
                    set(x, s, z, if (x == shootX + 1) "minecraft:yellow_concrete" else if (edge) "minecraft:smooth_stone" else "minecraft:polished_andesite")
                }
            }
            lampPost(shootX + 1, iz - 13)
            lampPost(shootX + 1, iz + 3)

            // one lane per firing distance: floor strip from the line to the target pad, a marker sign beside the pad
            LobbyTrainingLayout.RANGE_DISTANCES.forEachIndexed { i, d ->
                val z = iz + LobbyTrainingLayout.LANE_Z[i]
                val color = laneColors[i % laneColors.size]
                for (x in shootX + 2..shootX + d - 2) set(x, s, z, "minecraft:smooth_stone")
                for (dx in -1..1) for (dz in -1..1) set(shootX + d + dx, s, z + dz, "minecraft:${color}_concrete")
                set(shootX + d, s, z, "minecraft:target")
                val signPos = LobbyBlockPos(shootX + d, s + 1, z + 2)
                val signState = "minecraft:oak_sign[rotation=4]"
                set(signPos.x, signPos.y, signPos.z, signState)
                signs += LobbySign(
                    signPos,
                    signState,
                    listOf(
                        MessageKey("astera.lobby.sign.training.range.title"),
                        MessageKey("astera.lobby.sign.training.range.distance"),
                        null,
                        null,
                    ),
                    placeholders = mapOf("distance" to d.toString()),
                )
            }

            // melee pit: floor sunk one block, stone rim flush with the deck
            val pitX = ix + LobbyTrainingLayout.PIT_X
            val pitZ = iz + LobbyTrainingLayout.PIT_Z
            val pr = LobbyTrainingLayout.PIT_RADIUS
            for (dx in -(pr + 2)..pr + 2) {
                for (dz in -(pr + 2)..pr + 2) {
                    val d = hypot(dx.toDouble(), dz.toDouble())
                    val pos = LobbyBlockPos(pitX + dx, s, pitZ + dz)
                    when {
                        d <= pr + 0.5 -> {
                            blocks.remove(pos)
                            set(pos.x, s - 1, pos.z, "minecraft:red_sand")
                        }
                        d <= pr + 1.5 -> set(pos.x, s, pos.z, "minecraft:stone_bricks")
                    }
                }
            }
            run {
                val signPos = LobbyBlockPos(pitX, s + 1, pitZ - pr - 2)
                val signState = "minecraft:oak_sign[rotation=8]"
                set(signPos.x, signPos.y, signPos.z, signState)
                signs += LobbySign(
                    signPos,
                    signState,
                    listOf(MessageKey("astera.lobby.sign.training.melee.title"), MessageKey("astera.lobby.sign.training.melee.hint"), null, null),
                )
            }

            // moving target: a rail strip with end posts and the stand that aims at it
            val trackZ = iz + LobbyTrainingLayout.TRACK_Z
            for (x in LobbyTrainingLayout.TRACK_FROM_X..LobbyTrainingLayout.TRACK_TO_X) set(ix + x, s, trackZ, "minecraft:polished_blackstone")
            for (x in listOf(LobbyTrainingLayout.TRACK_FROM_X - 1, LobbyTrainingLayout.TRACK_TO_X + 1)) {
                set(ix + x, s, trackZ, "minecraft:polished_blackstone")
                set(ix + x, s + 1, trackZ, WALL)
                set(ix + x, s + 2, trackZ, "minecraft:lantern[hanging=false]")
            }
            val standX = ix + LobbyTrainingLayout.STAND_X
            val standZ = iz + LobbyTrainingLayout.STAND_Z
            for (dx in -1..1) for (dz in -1..1) set(standX + dx, s, standZ + dz, if (dx == 0 && dz == 0) "minecraft:yellow_concrete" else "minecraft:smooth_stone")
            run {
                val signPos = LobbyBlockPos(standX, s + 1, standZ - 2)
                val signState = "minecraft:oak_sign[rotation=8]"
                set(signPos.x, signPos.y, signPos.z, signState)
                signs += LobbySign(
                    signPos,
                    signState,
                    listOf(MessageKey("astera.lobby.sign.training.mover.title"), MessageKey("astera.lobby.sign.training.mover.hint"), null, null),
                )
            }

            // weapon rack: a backing wall in two groups with a walkway between them (the bridge arrives there)
            val wallX = ix + LobbyTrainingLayout.RACK_WALL_X
            for (dz in (-8..-2) + (2..8)) {
                for (y in 1..4) set(wallX, s + y, iz + dz, "minecraft:dark_oak_planks")
                set(wallX, s + 5, iz + dz, "minecraft:dark_oak_slab[type=bottom]")
            }
            for (dz in listOf(-5, 5)) {
                val signPos = LobbyBlockPos(wallX + 1, s + 4, iz + dz)
                val signState = "minecraft:oak_wall_sign[facing=east]"
                set(signPos.x, signPos.y, signPos.z, signState)
                signs += LobbySign(
                    signPos,
                    signState,
                    listOf(MessageKey("astera.lobby.sign.training.rack.title"), MessageKey("astera.lobby.sign.training.rack.hint"), null, null),
                )
            }
            for (dz in listOf(-8, 8)) lampPost(wallX + 1, iz + dz)
        }

        // ── garden, pavilion ────────────────────────────────────────────

        private fun gardenIsland(garden: Island) {
            val gx = garden.cx + 2
            val gz = garden.cz + 2
            // pond (kept well inside the rim so it never spills)
            for (dx in -3..3) for (dz in -3..3) {
                if (hypot(dx.toDouble(), dz.toDouble()) <= 2.6) set(gx + dx, s, gz + dz, "minecraft:water")
            }
            for (dx in -4..4) for (dz in -4..4) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d in 2.6..3.6 && get(gx + dx, s, gz + dz) == "minecraft:grass_block") set(gx + dx, s, gz + dz, "minecraft:mossy_cobblestone")
            }
            // stone benches and lantern by the pond
            val bench = "minecraft:stone_brick_stairs[facing=south,half=bottom,shape=straight]"
            for (k in -1..1) set(gx + k, s + 1, gz - 6, bench)
            lampPost(gx + 3, gz - 6)
            lampPost(gx - 3, gz - 6)
            // waterfall channel along z = garden.cz - 4 to the west rim
            channelToRim(garden.cx, garden.cz - 4, -1)
            // two-block lily-ish decor
            set(gx + 1, s + 1, gz, "minecraft:lily_pad")
            set(gx - 1, s + 1, gz + 1, "minecraft:lily_pad")
        }

        private fun pavilionIsland() {
            val px = cx
            val pz = cz - 42
            for (dx in -6..6) for (dz in -6..6) {
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d <= 5.2 && get(px + dx, s, pz + dz) != null) {
                    set(px + dx, s, pz + dz, if (d >= 4.4) "minecraft:smooth_stone" else "minecraft:polished_andesite")
                }
            }
            for (sx in listOf(-3, 3)) for (sz in listOf(-3, 3)) {
                for (y in 1..4) set(px + sx, s + y, pz + sz, "minecraft:stripped_cherry_log[axis=y]")
                set(px + sx, s + 5, pz + sz, "minecraft:cherry_planks")
            }
            for (dx in -4..4) for (dz in -4..4) {
                val edge = kotlin.math.abs(dx) == 4 || kotlin.math.abs(dz) == 4
                set(px + dx, s + 5, pz + dz, if (edge) "minecraft:cherry_slab[type=bottom]" else "minecraft:cherry_planks")
            }
            set(px, s + 6, pz, "minecraft:cherry_slab[type=bottom]")
            set(px, s + 4, pz, "minecraft:lantern[hanging=true]")
            // benches inside, facing the view (north)
            for (k in -1..1) set(px + k, s + 1, pz + 2, "minecraft:cherry_stairs[facing=south,half=bottom,shape=straight]")
            // low rails
            for (k in -2..2) {
                if (k != 0) set(px + k, s + 1, pz - 3, "minecraft:cherry_fence[east=true,west=true]")
            }
        }

        // ── water features ──────────────────────────────────────────────

        /** Water channel at row [z] running from the island centre [x] towards [dirX] until it spills over the rim. */
        private fun channelToRim(x: Int, z: Int, dirX: Int) {
            var cursor = x
            var last = x
            while (get(cursor, s, z) != null) {
                last = cursor
                cursor += dirX
            }
            var k = last
            repeat(5) {
                if (get(k, s, z) != null) set(k, s, z, "minecraft:water")
                k -= dirX
            }
        }

        private fun streamAndWaterfall(main: Island) {
            val px = main.cx - 15
            val pz = main.cz + 11
            for (dx in -2..2) for (dz in -2..2) {
                if (hypot(dx.toDouble(), dz.toDouble()) <= 2.2) set(px + dx, s, pz + dz, "minecraft:water")
            }
            var cursor = px
            while (get(cursor - 1, s, pz) != null) cursor--
            for (x in cursor..px) {
                if (get(x, s, pz) != null) set(x, s, pz, "minecraft:water")
            }
        }

        // ── decoration ──────────────────────────────────────────────────

        private fun decorate(isl: Island) {
            val ext = ceil(isl.r * 1.25).toInt()
            val y = isl.topY
            // trees first (they need clear grass), then ground cover
            repeat((isl.r * 1.0).toInt() + 3) {
                val ang = rng.nextDouble() * 2 * PI
                val t = 0.25 + rng.nextDouble() * 0.7
                val x = isl.cx + (cos(ang) * isl.radiusAt(ang) * t).roundToInt()
                val z = isl.cz + (sin(ang) * isl.radiusAt(ang) * t).roundToInt()
                if (trees.none { hypot((it.first - x).toDouble(), (it.second - z).toDouble()) < 4.5 } && grassAround(x, y, z)) {
                    val kind = rng.nextInt(10)
                    when {
                        kind < 4 -> cherry(x, y, z)
                        kind < 8 -> oak(x, y, z, "oak")
                        else -> oak(x, y, z, "birch")
                    }
                    trees += x to z
                }
            }
            for (dx in -ext..ext) {
                for (dz in -ext..ext) {
                    val x = isl.cx + dx
                    val z = isl.cz + dz
                    if (get(x, y, z) != "minecraft:grass_block" || get(x, y + 1, z) != null) continue
                    val r = rnd(x, z, 50)
                    when {
                        r < 0.05 -> set(x, y + 1, z, "minecraft:" + FLOWERS[(rnd(x, z, 51) * FLOWERS.size).toInt()])
                        r < 0.17 -> set(x, y + 1, z, "minecraft:short_grass")
                        r < 0.19 -> {
                            set(x, y + 1, z, "minecraft:tall_grass[half=lower]")
                            set(x, y + 2, z, "minecraft:tall_grass[half=upper]")
                        }
                        r < 0.215 -> {
                            set(x, y + 1, z, "minecraft:azalea_leaves[persistent=true]")
                            if (rnd(x, z, 52) < 0.4) set(x, y + 2, z, "minecraft:flowering_azalea_leaves[persistent=true]")
                        }
                    }
                }
            }
        }

        private fun grassAround(x: Int, y: Int, z: Int): Boolean {
            for (dx in -1..1) for (dz in -1..1) {
                val g = get(x + dx, y, z + dz)
                if (g != null && g != "minecraft:grass_block") return false
                if (get(x + dx, y + 1, z + dz) != null) return false
            }
            return get(x, y, z) == "minecraft:grass_block"
        }

        private fun leaf(x: Int, y: Int, z: Int, state: String) {
            if (get(x, y, z) == null) set(x, y, z, state)
        }

        private fun oak(x: Int, y: Int, z: Int, wood: String) {
            val h = 4 + rng.nextInt(3)
            for (k in 1..h) set(x, y + k, z, "minecraft:${wood}_log[axis=y]")
            for (dy in (h - 2)..(h + 1)) {
                val rad = if (dy >= h) 1 else 2
                for (dx in -rad..rad) for (dz in -rad..rad) {
                    val corner = kotlin.math.abs(dx) == 2 && kotlin.math.abs(dz) == 2
                    if (corner && rnd(x + dx, z + dz, dy) < 0.6) continue
                    leaf(x + dx, y + dy, z + dz, "minecraft:${wood}_leaves[persistent=true]")
                }
            }
        }

        private fun cherry(x: Int, y: Int, z: Int) {
            val h = 4 + rng.nextInt(2)
            for (k in 1..h) set(x, y + k, z, "minecraft:cherry_log[axis=y]")
            val radii = listOf(2, 3, 3, 2, 1)
            radii.forEachIndexed { i, rad ->
                val dy = h - 1 + i
                for (dx in -rad..rad) for (dz in -rad..rad) {
                    if (hypot(dx.toDouble(), dz.toDouble()) > rad + 0.4) continue
                    if (rnd(x + dx, z + dz, dy + 9) < 0.12) continue
                    leaf(x + dx, y + dy, z + dz, "minecraft:cherry_leaves[persistent=true]")
                }
            }
            for ((ax, az) in ARROWS) {
                if (get(x + ax * 2, y, z + az * 2) == "minecraft:grass_block" && get(x + ax * 2, y + 1, z + az * 2) == null) {
                    set(x + ax * 2, y + 1, z + az * 2, "minecraft:pink_petals[flower_amount=3,facing=north]")
                }
            }
        }
    }

    private const val WALL = "minecraft:stone_brick_wall"
    /** Z offset (south of the plaza centre) of the Play gate, between the fountain and the spawn tile. */
    private const val PLAY_GATE_DZ: Int = 4
    private const val TRAINING_SALT: Long = 0x7261696e696e67L

    /** The training deck is wide, so its underside is flattened to stay clear of the void-rescue line. */
    private const val TRAINING_DEPTH_SCALE: Double = 0.55
    private val ARROWS: List<Pair<Int, Int>> = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
    /** Axis direction to the satellite centre it leads to, with how far the path runs. */
    private val AXES: List<Pair<Pair<Int, Int>, Int>> = listOf(
        (1 to 0) to 47, (-1 to 0) to 44, (0 to 1) to 50, (0 to -1) to 42,
    )
    private val FLOWERS: List<String> = listOf(
        "dandelion", "poppy", "azure_bluet", "cornflower", "allium", "oxeye_daisy", "pink_tulip", "red_tulip",
    )
}
