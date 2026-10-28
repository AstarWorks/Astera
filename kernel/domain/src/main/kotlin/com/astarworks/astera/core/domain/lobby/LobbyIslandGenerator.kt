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
 *  - market island east (47, 0): shop (Market hub NPC) and vault stalls.
 *  - queue island south (0, 50): one pad + sign board per queue lane ([LobbyLanePadLayout]), queue master.
 *  - garden island west (-44, 0): pond and a waterfall over the rim.
 *  - pavilion island north (0, -42): gazebo viewpoint.
 *  - three small floating rocks with cherry trees.
 *  - training island east of the market (F3-11, [LobbyTrainingLayout]): firing lanes with backstops and cover,
 *    melee pit, moving-target track, weapon rack, an obstacle course, a cover field and gravel roads,
 *    joined to the main island by a bridge (MAP-07).
 *  - the four NPC hubs each have a building (MAP-07): Play gate house (spawn front), Travel station and Social
 *    pergola on the plaza, Market with a gate arch and three stalls; coloured ribbons in the plaza floor lead
 *    from the spawn to each.
 *  - the Guild Hall (MAP-07): a through-hall north of the fountain, built from the [LobbyStructurePart] layer
 *    (palette + layers text format), on the axis to the pavilion bridge.
 *  - the weapon test range (MAP-07): gun lanes, melee pit and a new staff bay, each with a roofed stand and a
 *    colour-coded floor ribbon from the entry gate.
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

    private enum class Stall { SHOP, GEAR, FRUIT }

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
            hubRibbons()
            playGate()
            travelStation()
            socialPlaza()
            guildHall()
            signposts()
            trainingIsland()
            weaponBays()
            trainingGround()
            marketIsland()
            queueIsland()
            gardenIsland(garden)
            pavilionIsland()
            streamAndWaterfall(main)
            plazaNpcs()

            allIslands.forEach { decorate(it) }
            toolBay() // after decorate: it clears any tree that grew into the yard
            return LobbyBuildPlan(
                blocks = blocks,
                signs = signs,
                spawn = Vec3(cx + 0.5, s + 1.0, cz + 6.5),
                spawnYaw = 180f,
                surfaceY = s,
                npcSpots = npcSpots,
                lanePads = lanePads,
                training = LobbyTrainingLayout.plan(cx, cz, s),
                toolBay = LobbyToolBayLayout.plan(cx, cz, s),
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
                    // The north side is the Travel station and the Social pergola (MAP-07): benches only in the south.
                    if (sz > 0) {
                        for (k in -1..1) {
                            set(cx + sx * 6 + k, s + 1, cz + sz * 6, "minecraft:spruce_stairs[facing=$facing,half=bottom,shape=straight]")
                        }
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
            // MAP-07: wings and corner towers make it a gate house, not a doorframe. Walls stop at |x| 4 so the
            // Travel / Social ribbons (x = +-5) walk around it.
            for (side in intArrayOf(-1, 1)) {
                for (h in 1..3) set(cx + side * 3, s + h, gz, "minecraft:quartz_bricks")
                set(cx + side * 3, s + 4, gz, "minecraft:quartz_slab[type=bottom]")
                for (h in 1..6) set(cx + side * 4, s + h, gz, "minecraft:quartz_pillar[axis=y]")
                set(cx + side * 4, s + 7, gz, "minecraft:sea_lantern")
                set(cx + side * 4, s + 8, gz, "minecraft:quartz_slab[type=bottom]")
                set(cx + side * 2, s + 6, gz, "minecraft:quartz_slab[type=bottom]")
            }
            for (dx in -1..1) set(cx + dx, s + 6, gz, "minecraft:quartz_slab[type=bottom]")
            // Gold band on the pillars: the "start here" colour of the plaza ribbons.
            for (side in intArrayOf(-2, 2)) set(cx + side, s + 3, gz, "minecraft:yellow_concrete")
            hubSign(cx - 2, s + 2, gz + 1, "south", "play")
            npcSpots["play-npc"] = LobbyNpcSpot(cx + 0.5, s + 1.0, gz + 0.5, 0f)
        }

        /** A wall sign naming a hub building; texts live under `astera.lobby.sign.hub.<hub>`. */
        private fun hubSign(x: Int, y: Int, z: Int, facing: String, hub: String) {
            val state = "minecraft:oak_wall_sign[facing=$facing]"
            set(x, y, z, state)
            signs += LobbySign(
                LobbyBlockPos(x, y, z),
                state,
                listOf(MessageKey("astera.lobby.sign.hub.$hub.title"), MessageKey("astera.lobby.sign.hub.$hub.subtitle"), null, null),
            )
        }

        private fun keySign(x: Int, y: Int, z: Int, state: String, base: String, second: String) {
            set(x, y, z, state)
            signs += LobbySign(
                LobbyBlockPos(x, y, z),
                state,
                listOf(MessageKey("astera.lobby.sign.$base.title"), MessageKey("astera.lobby.sign.$base.$second"), null, null),
            )
        }

        private fun box(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, state: String) {
            for (x in minOf(x1, x2)..maxOf(x1, x2)) for (y in minOf(y1, y2)..maxOf(y1, y2)) for (z in minOf(z1, z2)..maxOf(z1, z2)) set(x, y, z, state)
        }

        /**
         * Coloured ribbons inlaid in the plaza floor from the spawn tile to each hub (MAP-07): cyan to the Travel
         * station, lime to the Social pergola, orange east towards the Market bridge. Gold is the Play gate, straight ahead.
         */
        private fun hubRibbons() {
            fun tile(dx: Int, dz: Int, state: String) {
                val x = cx + dx
                val z = cz + dz
                if (get(x, s, z) != null && hypot(dx.toDouble(), dz.toDouble()) >= 3.7) set(x, s, z, state)
            }
            for (k in 2..5) {
                tile(-k, 5, "minecraft:cyan_concrete")
                tile(k, 5, "minecraft:lime_concrete")
            }
            for (z in -2..4) {
                tile(-5, z, "minecraft:cyan_concrete")
                tile(5, z, "minecraft:lime_concrete")
            }
            for (k in 6..9) tile(k, 1, "minecraft:orange_concrete")
        }

        /** Travel hub (x -7..-3, z -7..-3 from the plaza centre): a small station with a canopy, rail and departure board. */
        private fun travelStation() {
            val x0 = cx - 7
            val x1 = cx - 3
            val z0 = cz - 7
            val z1 = cz - 3
            for (x in x0..x1) for (z in z0..z1) {
                val edge = x == x0 || x == x1 || z == z0 || z == z1
                set(x, s, z, if (edge) "minecraft:light_blue_concrete" else if ((x + z) and 1 == 0) "minecraft:smooth_stone" else "minecraft:polished_andesite")
            }
            for (x in intArrayOf(x0, x1)) for (z in intArrayOf(z0, z1)) {
                for (h in 1..4) set(x, s + h, z, "minecraft:stripped_spruce_log[axis=y]")
            }
            for (x in x0 - 1..x1 + 1) for (z in z0 - 1..z1 + 1) {
                val edge = x == x0 - 1 || x == x1 + 1 || z == z0 - 1 || z == z1 + 1
                val glass = x in x0 + 1..x1 - 1 && z in z0 + 1..z1 - 1
                set(x, s + 5, z, if (edge) "minecraft:dark_oak_slab[type=bottom]" else if (glass) "minecraft:light_blue_stained_glass" else "minecraft:spruce_planks")
            }
            box(x0 + 1, s + 1, z0, x1 - 1, s + 3, z0, "minecraft:dark_oak_planks")
            hubSign(x0 + 2, s + 2, z0 + 1, "south", "travel")
            for (x in intArrayOf(x0 + 1, x1 - 1)) set(x, s + 4, z0 + 1, "minecraft:lantern[hanging=true]")
            // benches along the open south side, backs to the south
            set(x0 + 1, s + 1, z1, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
            set(x1 - 1, s + 1, z1, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
            // luggage
            set(x0, s + 1, z0 + 1, "minecraft:barrel[facing=up]")
            set(x0, s + 1, z0 + 2, "minecraft:barrel[facing=up]")
            set(x0, s + 2, z0 + 1, "minecraft:hay_block")
            // rail yard west of the station: one track on a gravel bed with a buffer stop
            for (z in z0 - 1..z1 + 1) {
                set(x0 - 1, s, z, "minecraft:gravel")
                set(x0 - 1, s + 1, z, "minecraft:rail[shape=north_south]")
            }
            set(x0 - 1, s + 1, z0 - 1, "minecraft:polished_blackstone_wall")
        }

        /** Social hub (x 3..7, z -7..-3): a flowering pergola with a table, seats and a planter wall. */
        private fun socialPlaza() {
            val x0 = cx + 3
            val x1 = cx + 7
            val z0 = cz - 7
            val z1 = cz - 3
            val mx = cx + 5
            val mz = cz - 5
            for (x in x0..x1) for (z in z0..z1) {
                val edge = x == x0 || x == x1 || z == z0 || z == z1
                val d = hypot((x - mx).toDouble(), (z - mz).toDouble())
                set(x, s, z, if (edge) "minecraft:lime_concrete" else if (d <= 1.5) "minecraft:pink_terracotta" else "minecraft:white_terracotta")
            }
            for (x in intArrayOf(x0, x1)) for (z in intArrayOf(z0, z1)) {
                for (h in 1..4) set(x, s + h, z, "minecraft:stripped_cherry_log[axis=y]")
            }
            for (x in x0..x1) for (z in z0..z1) {
                val edge = x == x0 || x == x1 || z == z0 || z == z1
                if (!edge) continue
                set(x, s + 4, z, "minecraft:cherry_slab[type=bottom]")
                if ((x + z) and 1 == 0) set(x, s + 5, z, "minecraft:flowering_azalea_leaves[persistent=true]")
            }
            for ((x, z) in listOf(x0 + 1 to z0 + 1, x1 - 1 to z0 + 1, x0 + 1 to z1 - 1, x1 - 1 to z1 - 1)) {
                set(x, s + 3, z, "minecraft:lantern[hanging=true]")
            }
            // planter wall with the name board
            box(x0 + 1, s + 1, z0, x1 - 1, s + 2, z0, "minecraft:cherry_planks")
            hubSign(mx, s + 2, z0 + 1, "south", "social")
            for (x in x0 + 1..x1 - 1) set(x, s + 3, z0, "minecraft:flowering_azalea_leaves[persistent=true]")
            // table with four seats (stairs: facing = side of the back)
            val tx = cx + 6
            val tz = cz - 5
            set(tx, s + 1, tz, "minecraft:cherry_fence")
            set(tx, s + 2, tz, "minecraft:cherry_pressure_plate")
            set(tx + 1, s + 1, tz, "minecraft:cherry_stairs[facing=east,half=bottom,shape=straight]")
            set(tx - 1, s + 1, tz, "minecraft:cherry_stairs[facing=west,half=bottom,shape=straight]")
            set(tx, s + 1, tz - 1, "minecraft:cherry_stairs[facing=north,half=bottom,shape=straight]")
            set(tx, s + 1, tz + 1, "minecraft:cherry_stairs[facing=south,half=bottom,shape=straight]")
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
            val cells = mapOf("travel-npc" to (-4 to -4), "social-npc" to (4 to -4))
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
            stall(mx, mz - 6, 0, 1, Stall.SHOP, "market-npc")
            // The gear stall is the vault counter: scenery only (UI-01: four role hubs, no gear-keeper NPC).
            stall(mx, mz + 6, 0, -1, Stall.GEAR, null)
            // MAP-07: a produce stall on the east side, crates by the entrance and a gate arch at the bridge head.
            stall(mx + 6, mz, -1, 0, Stall.FRUIT, null)
            for (side in intArrayOf(-1, 1)) {
                set(mx - 6, s + 1, mz + side * 4, "minecraft:barrel[facing=up]")
                set(mx - 6, s + 2, mz + side * 4, "minecraft:hay_block")
                set(mx - 7, s + 1, mz + side * 4, "minecraft:hay_block")
            }
            marketGate(mx - 9, mz)
        }

        /** Gate arch at the west (bridge) end of the market, facing the bridge, with the Market name board. */
        private fun marketGate(gx: Int, gz: Int) {
            for (side in intArrayOf(-2, 2)) {
                if (get(gx, s, gz + side) == null) continue
                for (h in 1..4) set(gx, s + h, gz + side, "minecraft:stripped_oak_log[axis=y]")
                set(gx, s + 5, gz + side, "minecraft:red_wool")
            }
            for (dz in -2..2) set(gx, s + 5, gz + dz, if (dz and 1 == 0) "minecraft:red_wool" else "minecraft:white_wool")
            for (dz in -1..1) set(gx, s + 6, gz + dz, "minecraft:oak_slab[type=bottom]")
            for (dz in intArrayOf(-1, 1)) set(gx, s + 4, gz + dz, "minecraft:lantern[hanging=true]")
            if (get(gx, s, gz + 2) != null) hubSign(gx - 1, s + 3, gz + 2, "west", "market")
        }

        /** Market stall: NPC at (nx, nz), customers approach from direction (dx, dz). */
        private fun stall(nx: Int, nz: Int, dx: Int, dz: Int, kind: Stall, npcId: String?) {
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
                Stall.FRUIT -> "minecraft:green_wool" to "minecraft:white_wool"
            }
            for (u in -3..3) {
                for (v in -2..2) w(u, v, s + 4, if (u and 1 == 0) roofA else roofB)
                // back wall
                val wall = when (kind) {
                    Stall.SHOP -> "minecraft:oak_planks"
                    Stall.GEAR -> "minecraft:spruce_planks"
                    Stall.FRUIT -> "minecraft:birch_planks"
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
                    Stall.FRUIT -> w(u, -2, s + 2, if (u and 1 == 0) "minecraft:hay_block" else "minecraft:birch_planks")
                }
            }
            if (npcId != null) {
                npcSpots[npcId] = LobbyNpcSpot(
                    nx + 0.5, s + 1.0, nz + 0.5,
                    Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat() + 0f,
                )
            }
        }

        private fun counterBlock(kind: Stall, u: Int, face: String): String = when (kind) {
            Stall.SHOP -> if (u == -2 || u == 2) "minecraft:barrel[facing=up]" else "minecraft:oak_planks"
            Stall.GEAR -> when (u) {
                -1, 1 -> "minecraft:loom[facing=$face]"
                0 -> "minecraft:spruce_planks"
                else -> "minecraft:barrel[facing=up]"
            }
            Stall.FRUIT -> when (u) {
                -2, 2 -> "minecraft:hay_block"
                -1, 1 -> "minecraft:melon"
                else -> "minecraft:pumpkin"
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

        // ── training ground (MAP-07) ────────────────────────────────────

        /**
         * Dresses the training deck around the F3-11 stations: roads, target backstops, cover between the firing
         * lanes, an obstacle course (north), a cover field (south-east), an entry gate and a ring of trees.
         * Nothing here touches the dummy / rack / pit coordinates of [LobbyTrainingLayout]; every write is
         * guarded by "is there deck here" so a thin island rim never grows floating blocks.
         */
        private fun trainingGround() {
            val ix = cx + LobbyTrainingLayout.ISLAND_X
            val iz = cz + LobbyTrainingLayout.ISLAND_Z
            val tr = Random(seed xor TRAINING_SALT xor 0x6d617037L)
            fun deck(x: Int, z: Int) = get(x, s, z) != null
            fun put(x: Int, y: Int, z: Int, state: String) {
                if (deck(x, z)) set(x, y, z, state)
            }

            // yard: patchy dirt and gravel instead of a flat lawn
            for (dx in -30..30) for (dz in -30..30) {
                val x = ix + dx
                val z = iz + dz
                if (get(x, s, z) != "minecraft:grass_block") continue
                val r = rnd(x, z, 70)
                if (r < 0.06) set(x, s, z, "minecraft:coarse_dirt") else if (r < 0.09) set(x, s, z, "minecraft:gravel")
            }

            // roads: bridge landing -> firing platform, the spine south of the lanes, branches to each station
            fun road(x1: Int, z1: Int, x2: Int, z2: Int) {
                for (x in ix + minOf(x1, x2)..ix + maxOf(x1, x2)) for (z in iz + minOf(z1, z2)..iz + maxOf(z1, z2)) {
                    val cur = get(x, s, z)
                    if (cur != "minecraft:grass_block" && cur != "minecraft:coarse_dirt" && cur != "minecraft:gravel") continue
                    val r = rnd(x, z, 71)
                    set(x, s, z, if (r < 0.55) "minecraft:gravel" else if (r < 0.8) "minecraft:dirt_path" else "minecraft:coarse_dirt")
                }
            }
            road(-31, -1, -24, 1)
            road(-22, 3, 22, 4)
            road(-12, 5, -11, 8)
            road(7, 5, 9, 5)
            road(15, 5, 16, 6)
            road(-21, -19, -20, -14)
            road(-19, -19, -16, -19)
            for (dx in intArrayOf(-14, -4, 4, 12, 20)) if (deck(ix + dx, iz + 5)) lampPost(ix + dx, iz + 5)

            // target backstops: hay behind every dummy pad, a bullseye at the middle
            LobbyTrainingLayout.RANGE_DISTANCES.forEachIndexed { i, d ->
                val bx = ix + LobbyTrainingLayout.SHOOT_X + d + 2
                val lz = iz + LobbyTrainingLayout.LANE_Z[i]
                for (dz in -1..1) {
                    put(bx, s + 1, lz + dz, "minecraft:hay_block")
                    put(bx, s + 2, lz + dz, "minecraft:hay_block")
                    put(bx, s + 3, lz + dz, "minecraft:dark_oak_slab[type=bottom]")
                }
                put(bx, s + 1, lz, "minecraft:target")
                put(bx, s + 2, lz, "minecraft:target")
            }

            // cover between the firing lanes: low walls a shooter can duck behind (the lanes themselves stay clear)
            for (dz in intArrayOf(-9, -5, -1)) {
                for (dx in -16..-14) {
                    put(ix + dx, s, iz + dz, "minecraft:polished_andesite")
                    put(ix + dx, s + 1, iz + dz, "minecraft:stone_brick_wall")
                }
                put(ix - 17, s + 1, iz + dz, "minecraft:barrel[facing=up]")
            }

            // obstacle course: a 3-wide corridor, start (lime) in the west, finish (red) in the east
            val cz0 = iz - 19
            val xs = ix - 15
            val xe = ix + 15
            for (x in xs..xe) {
                for (z in cz0 - 1..cz0 + 1) put(x, s, z, "minecraft:light_gray_concrete")
                for (z in intArrayOf(cz0 - 2, cz0 + 2)) {
                    put(x, s, z, "minecraft:smooth_stone")
                    put(x, s + 1, z, "minecraft:oak_fence[east=true,west=true]")
                }
            }
            for (z in cz0 - 1..cz0 + 1) {
                put(xs, s, z, "minecraft:lime_concrete")
                put(xe, s, z, "minecraft:red_concrete")
            }
            fun across(ox: Int, h: Int, state: String) {
                for (z in cz0 - 1..cz0 + 1) for (y in 1..h) put(ix + ox, s + y, z, state)
            }
            across(-11, 1, "minecraft:stone_bricks") // hurdle
            across(-8, 1, "minecraft:stone_bricks") // step up ...
            across(-7, 2, "minecraft:stone_bricks") // ... onto a two-high wall
            across(-6, 2, "minecraft:stone_bricks")
            listOf(-3 to 1, -2 to 2, -1 to 3, 0 to 3, 1 to 3, 2 to 2, 3 to 1).forEach { (ox, h) -> across(ox, h, "minecraft:polished_andesite") } // ziggurat
            for ((ox, openZ) in listOf(6 to cz0 - 1, 8 to cz0 + 1, 10 to cz0 - 1)) { // slalom walls with one gap each
                for (z in cz0 - 1..cz0 + 1) if (z != openZ) for (y in 1..3) put(ix + ox, s + y, z, "minecraft:light_blue_concrete")
            }
            for (ox in 11..13) for (z in cz0 - 1..cz0 + 1) { // balance beam over a water trough
                put(ix + ox, s, z, if (z == cz0) "minecraft:spruce_planks" else "minecraft:water")
            }
            for ((ox, post, lintel) in listOf(
                Triple(xs - ix + 1, "minecraft:stripped_oak_log[axis=y]", "minecraft:lime_wool"),
                Triple(xe - ix - 1, "minecraft:quartz_pillar[axis=y]", "minecraft:red_wool"),
            )) {
                for (z in intArrayOf(cz0 - 2, cz0 + 2)) for (h in 1..4) put(ix + ox, s + h, z, post)
                for (z in cz0 - 2..cz0 + 2) put(ix + ox, s + 5, z, lintel)
                put(ix + ox, s + 4, cz0, "minecraft:lantern[hanging=true]")
            }
            if (deck(xs - 2, cz0)) keySign(xs - 2, s + 1, cz0, "minecraft:oak_sign[rotation=4]", "training.course", "hint")

            // cover field for ranged practice: walls, barriers and crate stacks facing a hay backstop to the south
            for (ox in 12..14) put(ix + ox, s + 1, iz + 9, "minecraft:cobblestone_wall")
            for (ox in 18..20) put(ix + ox, s + 1, iz + 9, "minecraft:cobblestone_wall")
            for (oz in 10..11) for (y in 1..2) put(ix + 16, s + y, iz + oz, "minecraft:gray_concrete")
            for (ox in intArrayOf(13, 14, 19, 20)) put(ix + ox, s + 1, iz + 11, "minecraft:barrel[facing=up]")
            put(ix + 13, s + 2, iz + 11, "minecraft:hay_block")
            put(ix + 20, s + 2, iz + 11, "minecraft:hay_block")
            for (ox in 11..21) {
                put(ix + ox, s + 1, iz + 13, if ((ox - 11) % 3 == 1) "minecraft:target" else "minecraft:hay_block")
                put(ix + ox, s + 2, iz + 13, "minecraft:hay_block")
            }
            for (ox in intArrayOf(12, 16, 20)) put(ix + ox, s, iz + 7, "minecraft:yellow_concrete")
            if (deck(ix + 11, iz + 6)) keySign(ix + 11, s + 1, iz + 6, "minecraft:oak_sign[rotation=8]", "training.cover", "hint")

            // entry gate at the bridge landing, facing the bridge
            val gx = ix - 26
            for (z in intArrayOf(iz - 2, iz + 2)) {
                for (h in 1..4) put(gx, s + h, z, "minecraft:dark_oak_log[axis=y]")
                put(gx, s + 5, z, "minecraft:sea_lantern")
            }
            for (z in iz - 2..iz + 2) put(gx, s + 5, z, "minecraft:dark_oak_planks")
            for (z in intArrayOf(iz - 1, iz + 1)) put(gx, s + 4, z, "minecraft:lantern[hanging=true]")
            if (deck(gx - 1, iz + 2)) keySign(gx - 1, s + 3, iz + 2, "minecraft:oak_wall_sign[facing=west]", "training.entry", "hint")
            if (deck(gx - 1, iz - 2)) keySign(gx - 1, s + 3, iz - 2, "minecraft:oak_wall_sign[facing=west]", "training.guide", "hint")

            // a loose ring of trees around the rim, never near the stations
            val planted = ArrayList<Pair<Int, Int>>()
            for (k in 0 until 40) {
                val ang = k * 2 * PI / 40 + tr.nextDouble() * 0.15
                val rad = 24.0 + tr.nextDouble() * 3.5
                val x = ix + (cos(ang) * rad).roundToInt()
                val z = iz + (sin(ang) * rad).roundToInt()
                if (planted.any { hypot((it.first - x).toDouble(), (it.second - z).toDouble()) < 4.5 }) continue
                if (!grassAround(x, s, z)) continue
                oak(x, s, z, if (tr.nextInt(4) == 0) "birch" else "oak", tr)
                planted += x to z
            }

            // ground cover last, only on bare grass
            for (dx in -30..30) for (dz in -30..30) {
                val x = ix + dx
                val z = iz + dz
                if (get(x, s, z) != "minecraft:grass_block" || get(x, s + 1, z) != null) continue
                val r = rnd(x, z, 72)
                when {
                    r < 0.015 -> set(x, s + 1, z, "minecraft:" + FLOWERS[(rnd(x, z, 73) * FLOWERS.size).toInt()])
                    r < 0.10 -> set(x, s + 1, z, "minecraft:short_grass")
                }
            }
        }

        // ── guild hall (MAP-07) ─────────────────────────────────────────

        /**
         * The Guild Hall: the town's front building, a through-hall north of the fountain (x -8..8, z -20..-13 from
         * the plaza centre) that every player sees from the spawn tile. Doors face the plaza and the pavilion bridge
         * (the north axis path runs through it). Walls are the [GUILD_HALL] part; the gabled roof, tables, banners
         * and chandeliers are placed here.
         */
        private fun guildHall() {
            val x0 = cx - 8
            val z0 = cz - 20
            // Clear whatever the ring path / lamp posts left in the footprint (and the eaves).
            for (x in x0 - 1..x0 + 17) for (z in z0 - 1..z0 + 8) for (y in 1..16) blocks.remove(LobbyBlockPos(x, s + y, z))
            GUILD_HALL.place(x0, s, z0, 0) { x, y, z, state -> set(x, y, z, state) }

            // gabled roof along x: the north slope faces north, the south slope south, ridge at s + 10
            for (n in 0..4) {
                for (x in x0 - 1..x0 + 17) {
                    set(x, s + 6 + n, z0 - 1 + n, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
                    set(x, s + 6 + n, z0 + 8 - n, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
                }
                // gable ends: fill the triangle under each slope step
                for (y in s + 6 until s + 6 + n) for (x in intArrayOf(x0, x0 + 16)) {
                    set(x, y, z0 - 1 + n, "minecraft:spruce_planks")
                    set(x, y, z0 + 8 - n, "minecraft:spruce_planks")
                }
            }
            // pennant mast on the ridge: readable from every island
            val mz = z0 + 3
            for (h in 11..14) set(cx, s + h, mz, "minecraft:dark_oak_fence")
            set(cx, s + 15, mz, "minecraft:sea_lantern")
            for (h in 12..13) for (dx in 1..2) set(cx + dx, s + h, mz, "minecraft:yellow_wool")

            // facade: team-colour banners on the outer pillars, lanterns under the door lintels, name boards
            for ((dx, wool) in listOf(-5 to "red_wool", 5 to "blue_wool")) {
                for (h in 2..4) set(cx + dx, s + h, z0 + 8, "minecraft:$wool")
            }
            for (dx in intArrayOf(-1, 1)) {
                set(cx + dx, s + 3, z0 + 7, "minecraft:lantern[hanging=true]")
                set(cx + dx, s + 3, z0, "minecraft:lantern[hanging=true]")
            }
            hubSign(cx - 2, s + 2, z0 + 8, "south", "guild")
            hubSign(cx + 2, s + 2, z0 + 8, "south", "guild")
            hubSign(cx - 2, s + 2, z0 - 1, "north", "guild")
            lampPost(cx - 10, z0 + 8)
            lampPost(cx + 10, z0 + 8)
            lampPost(cx - 10, z0)
            lampPost(cx + 10, z0)

            // interior: red aisle, two long tables with benches, bookshelf walls, chandeliers
            for (z in z0 + 1..z0 + 6) for (dx in -1..1) set(cx + dx, s + 1, z, "minecraft:red_carpet")
            for (side in intArrayOf(-1, 1)) {
                for (k in 4..6) {
                    set(cx + side * k, s + 1, z0 + 3, "minecraft:dark_oak_slab[type=bottom]")
                    set(cx + side * k, s + 1, z0 + 2, "minecraft:spruce_stairs[facing=south,half=bottom,shape=straight]")
                    set(cx + side * k, s + 1, z0 + 4, "minecraft:spruce_stairs[facing=north,half=bottom,shape=straight]")
                }
                set(cx + side * 5, s + 2, z0 + 3, "minecraft:lantern[hanging=false]")
                for (k in 3..6) for (h in 1..2) set(cx + side * k, s + h, z0 + 1, "minecraft:bookshelf")
                for (h in 6..9) set(cx + side * 3, s + h, z0 + 3, "minecraft:iron_chain[axis=y]")
                set(cx + side * 3, s + 5, z0 + 3, "minecraft:lantern[hanging=true]")
            }
        }

        // ── weapon test range (MAP-07) ──────────────────────────────────

        /**
         * Turns the training deck into a weapon test range a first-timer can read: three colour-coded bays (gun
         * yellow, sword red, staff purple), each with a roofed stand and a name board, floor ribbons from the entry
         * gate to each, and the staff bay itself (three lanes of targets). The gun lanes and the melee pit are the
         * F3-11 stations built by [trainingIsland]; this only dresses them.
         */
        /**
         * Tool test bay (MAP-A6): walled yard east of the weapon range. North = stone-type blocks, south = dirt-type,
         * east = wood-type (the three Annihilation pillar materials are among them), open floor between, the tool
         * rack and reset button on the west wall. Geometry lives in [LobbyToolBayLayout].
         */
        private fun toolBay() {
            val ix = cx + LobbyTrainingLayout.ISLAND_X
            val iz = cz + LobbyTrainingLayout.ISLAND_Z
            val x0 = ix + LobbyToolBayLayout.WALL_X
            val x1 = ix + LobbyToolBayLayout.MAX_X
            val z0 = iz + LobbyToolBayLayout.MIN_Z
            val z1 = iz + LobbyToolBayLayout.MAX_Z
            // clear whatever grew here (trees), then lay the floor
            for (x in x0 - 1..x1 + 1) for (z in z0 - 1..z1 + 1) {
                for (y in s + 1..s + 18) blocks.remove(LobbyBlockPos(x, y, z))
            }
            for (x in x0..x1) for (z in z0..z1) {
                set(x, s - 1, z, "minecraft:stone_bricks")
                set(x, s, z, if (x == x0 + 1) "minecraft:polished_andesite" else "minecraft:smooth_stone")
            }
            val stone = listOf("stone", "cobblestone", "andesite", "polished_deepslate", "stone", "cobblestone", "andesite")
            val dirt = listOf("dirt", "coarse_dirt", "rooted_dirt", "mud", "clay", "podzol[snowy=false]", "coarse_dirt")
            val wood = listOf(
                "stripped_dark_oak_wood[axis=y]", "dark_oak_log[axis=y]", "oak_log[axis=y]", "spruce_log[axis=y]",
                "birch_log[axis=y]", "stripped_dark_oak_wood[axis=y]", "oak_log[axis=y]",
            )
            val bx0 = ix + LobbyToolBayLayout.REGION_MIN_X
            for (x in bx0..x1) {
                for (y in s + 1..s + 4) {
                    for (z in z0..z0 + 2) set(x, y, z, "minecraft:" + stone[(x - bx0) % stone.size])
                    for (z in z1 - 2..z1) set(x, y, z, "minecraft:" + dirt[(x - bx0) % dirt.size])
                }
            }
            for (x in x1 - 2..x1) for (z in z0 + 3..z1 - 3) for (y in s + 1..s + 4) {
                set(x, y, z, "minecraft:" + wood[(z - z0) % wood.size])
            }
            // west wall: rack backing, gate gap at z 3..4
            for (z in z0..z1) {
                if (z - iz in LobbyToolBayLayout.GATE_Z1..LobbyToolBayLayout.GATE_Z2) continue
                for (y in s + 1..s + 5) set(x0, y, z, "minecraft:stone_bricks")
            }
            // gate posts with lanterns
            for (zz in listOf(iz + LobbyToolBayLayout.GATE_Z1 - 1, iz + LobbyToolBayLayout.GATE_Z2 + 1)) {
                set(x0, s + 6, zz, "minecraft:lantern[hanging=false]")
            }
            val plan = LobbyToolBayLayout.plan(cx, cz, s)
            val button = plan.resetButton
            set(button.x, button.y, button.z, "minecraft:stone_button[face=wall,facing=east]")
            fun wallSign(pos: LobbyBlockPos, base: String) {
                val state = "minecraft:oak_wall_sign[facing=east]"
                set(pos.x, pos.y, pos.z, state)
                signs += LobbySign(
                    pos,
                    state,
                    listOf(MessageKey("astera.lobby.sign.training.bay.$base.title"), MessageKey("astera.lobby.sign.training.bay.$base.hint"), null, null),
                )
            }
            wallSign(LobbyBlockPos(x0 + 1, s + 5, iz - 1), "tools")
            wallSign(LobbyBlockPos(button.x, button.y + 1, button.z), "reset")
        }

        private fun weaponBays() {
            val ix = cx + LobbyTrainingLayout.ISLAND_X
            val iz = cz + LobbyTrainingLayout.ISLAND_Z
            fun soil(x: Int, z: Int): Boolean = when (get(x, s, z)) {
                "minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:gravel", "minecraft:dirt_path" -> true
                else -> false
            }
            fun ground(x: Int, z: Int, state: String) {
                if (soil(x, z)) set(x, s, z, state)
            }
            fun ribbon(x1: Int, z1: Int, x2: Int, z2: Int, color: String) {
                for (x in ix + minOf(x1, x2)..ix + maxOf(x1, x2)) for (z in iz + minOf(z1, z2)..iz + maxOf(z1, z2)) ground(x, z, "minecraft:${color}_concrete")
            }
            fun booth(x0: Int, z0: Int, color: String, backRow: Boolean, base: String) {
                for (x in x0..x0 + 4) for (z in z0..z0 + 6) ground(x, z, "minecraft:smooth_stone")
                boothPart(color, backRow).place(x0, s + 1, z0, 3) { x, y, z, state -> if (get(x, s, z) != null) set(x, y, z, state) }
                if (backRow) {
                    val signState = "minecraft:oak_wall_sign[facing=east]"
                    set(x0 + 1, s + 2, z0 + 3, signState)
                    signs += LobbySign(
                        LobbyBlockPos(x0 + 1, s + 2, z0 + 3),
                        signState,
                        listOf(MessageKey("astera.lobby.sign.training.bay.$base.title"), MessageKey("astera.lobby.sign.training.bay.$base.hint"), null, null),
                    )
                }
            }

            // ribbons first, so the gravel roads laid by trainingGround() leave them alone
            // (they start at the firing platform's south edge, where the spine road begins; the rack wall bars the west)
            ribbon(-25, 0, -21, 0, "yellow") // gun: from the bridge landing straight onto the firing platform
            ribbon(-21, 5, -17, 5, "red") // sword: to the stand west of the pit
            ribbon(-17, 5, -17, 10, "red")
            ribbon(-21, 6, 1, 6, "purple") // staff: along the spine, then south to the stand
            ribbon(1, 6, 1, 17, "purple")

            // gun: a roof over the firing platform (the rack frames sit behind it, so no back wall)
            val shootX = ix + LobbyTrainingLayout.SHOOT_X
            booth(shootX - 2, iz - 8, "yellow", false, "gun")
            // the gun bay is named on a board by the platform's north lamp
            run {
                val signState = "minecraft:oak_sign[rotation=4]"
                set(shootX + 2, s + 1, iz - 9, signState)
                signs += LobbySign(
                    LobbyBlockPos(shootX + 2, s + 1, iz - 9),
                    signState,
                    listOf(MessageKey("astera.lobby.sign.training.bay.gun.title"), MessageKey("astera.lobby.sign.training.bay.gun.hint"), null, null),
                )
            }
            // sword: a stand west of the pit, open to it
            booth(ix + LobbyTrainingLayout.PIT_X - 9, iz + LobbyTrainingLayout.PIT_Z - 3, "red", true, "sword")
            // staff: a stand and three lanes of targets (the dummies come from LobbyTrainingLayout.plan)
            val staffX = ix + LobbyTrainingLayout.STAFF_STAND_X
            booth(staffX - 2, iz + LobbyTrainingLayout.STAFF_LANE_Z[0] - 1, "purple", true, "staff")
            LobbyTrainingLayout.STAFF_DISTANCES.forEachIndexed { i, d ->
                val z = iz + LobbyTrainingLayout.STAFF_LANE_Z[i]
                for (x in staffX + 3..staffX + d - 2) ground(x, z, "minecraft:smooth_stone")
                for (dx in -1..1) for (dz in -1..1) ground(staffX + d + dx, z + dz, "minecraft:purple_concrete")
                ground(staffX + d, z, "minecraft:amethyst_block")
                for (dz in -1..1) {
                    if (get(staffX + d + 2, s, z + dz) != null) {
                        set(staffX + d + 2, s + 1, z + dz, "minecraft:purple_terracotta")
                        set(staffX + d + 2, s + 2, z + dz, "minecraft:purple_terracotta")
                    }
                }
                if (get(staffX + d + 2, s, z) != null) set(staffX + d + 2, s + 3, z, "minecraft:lantern[hanging=false]")
            }
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

        private fun oak(x: Int, y: Int, z: Int, wood: String, random: Random = rng) {
            val h = 4 + random.nextInt(3)
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

    private val HALL_GAP: String = ".".repeat(15)

    /**
     * The Guild Hall shell, 17 wide x 8 deep (row 0 = north). Layers are y + 0..5: floor with a quartz aisle,
     * a stone base, two windows rows, the lintel course and the wall plate. Doors are the 3-wide gaps at the middle
     * of the north and south walls; the roof is placed by the generator.
     */
    private val GUILD_HALL: LobbyStructurePart = run {
        // [front] is the north / south wall row; [pattern] gives the side-wall char per row (pillars at rows 3-4)
        fun layer(front: String, pattern: String): List<String> =
            (0..7).map { r -> if (r == 0 || r == 7) front else "${pattern[r]}$HALL_GAP${pattern[r]}" }
        LobbyStructurePart(
            palette = mapOf(
                'P' to "minecraft:polished_andesite",
                'Q' to "minecraft:smooth_quartz",
                'L' to "minecraft:stripped_spruce_log[axis=y]",
                'S' to "minecraft:stone_bricks",
                'G' to "minecraft:glass",
                'T' to "minecraft:dark_oak_planks",
            ),
            layers = listOf(
                List(8) { "PPPPPPPQQQPPPPPPP" },
                layer("LSSLSSL...LSSLSSL", "LSSSSSSL"),
                layer("LGGLGGL...LGGLGGL", "LGGLLGGL"),
                layer("LGGLGGL...LGGLGGL", "LGGLLGGL"),
                layer("LTTLTTLTTTLTTLTTL", "LTTTTTTL"),
                List(8) { r -> if (r == 0 || r == 7) "T".repeat(17) else "T${HALL_GAP}T" },
            ),
        )
    }

    /**
     * A roofed weapon stand, 7 wide x 5 deep (row 0 = back), layers from y + 0 up: posts and a barrel counter,
     * a coloured panel (the bay colour) on the back wall, a hanging lantern, the roof. [backRow] false switches the
     * counter and panel off (the gun stand sits in front of the weapon rack and must not hide its frames).
     */
    private fun boothPart(color: String, backRow: Boolean): LobbyStructurePart {
        val none = ".......".let { listOf(it, it, it) }
        return LobbyStructurePart(
            palette = mapOf(
                'P' to "minecraft:stripped_dark_oak_log[axis=y]",
                'C' to if (backRow) "minecraft:barrel[facing=up]" else "minecraft:air",
                'T' to if (backRow) "minecraft:${color}_wool" else "minecraft:air",
                'L' to "minecraft:lantern[hanging=true]",
                'R' to "minecraft:${color}_terracotta",
                'S' to "minecraft:dark_oak_slab[type=bottom]",
            ),
            layers = listOf(
                listOf("PCCCCCP") + none + listOf("P.....P"),
                listOf("PTTTTTP") + none + listOf("P.....P"),
                listOf("PTTTTTP", ".......", "...L...", ".......", "P.....P"),
                List(5) { "RRRRRRR" },
                listOf(".......", ".SSSSS.", ".SSSSS.", ".SSSSS.", "......."),
            ),
        )
    }
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
