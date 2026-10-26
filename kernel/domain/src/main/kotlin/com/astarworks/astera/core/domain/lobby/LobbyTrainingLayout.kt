package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Geometry of the lobby training island (ADR-0200, F3-11), east of the market island and joined to
 * the main island by a bridge. Offsets are relative to the plaza centre `(centerX, centerZ)`; +x east,
 * +z south. Every number lives here so [LobbyIslandGenerator] (blocks) and [plan] (landmarks) cannot drift.
 *
 * ```
 *  rack wall | firing platform | 4 lanes (dummies at 5 / 10 / 20 / 40) ->
 *            |                 |
 *   melee pit (SW)         stand + moving-target track (S)
 * ```
 */
public object LobbyTrainingLayout {

    /** Island centre and radius, relative to the plaza centre. */
    public const val ISLAND_X: Int = 104
    public const val ISLAND_Z: Int = -18
    public const val ISLAND_RADIUS: Double = 34.0

    /** Bridge from the main island (starts inside it, ends inside the training island). */
    public const val BRIDGE_FROM_X: Int = 8
    public const val BRIDGE_TO_X: Int = ISLAND_X - 26

    /** Firing distances, one lane each (a dummy in front would otherwise block the shots behind it). */
    public val RANGE_DISTANCES: List<Int> = listOf(5, 10, 20, 40)

    /** Lane Z offsets from the island centre, one per entry of [RANGE_DISTANCES]. */
    public val LANE_Z: List<Int> = listOf(-11, -7, -3, 1)

    /** X offset (from the island centre) where shooters stand; dummies are `distance` blocks east of it. */
    public const val SHOOT_X: Int = -20

    /**
     * Staff bay (MAP-07): a third weapon family on the deck. The caster stands [STAFF_STAND_X] east of the island
     * centre; one target per lane, [STAFF_DISTANCES] blocks east of the stand, lanes [STAFF_LANE_Z] south of the centre.
     */
    public const val STAFF_STAND_X: Int = 2
    public val STAFF_DISTANCES: List<Int> = listOf(5, 8, 11)
    public val STAFF_LANE_Z: List<Int> = listOf(19, 21, 23)

    /** Melee pit centre offset and radius (floor sunk one block). */
    public const val PIT_X: Int = -10
    public const val PIT_Z: Int = 14
    public const val PIT_RADIUS: Int = 4

    /** Moving target track and the stand that aims at it. */
    public const val TRACK_FROM_X: Int = -2
    public const val TRACK_TO_X: Int = 18
    public const val TRACK_Z: Int = 15
    public const val STAND_X: Int = 8
    public const val STAND_Z: Int = 7
    public const val TRACK_SPEED: Double = 0.12

    /** Weapon rack: backing wall at [RACK_WALL_X], frames one cell east of it, [RACK_ROWS] rows. */
    public const val RACK_WALL_X: Int = -24
    public const val RACK_ROWS: Int = 3
    public val RACK_COLUMN_Z: List<Int> = listOf(-7, -6, -5, -4, -3, -2, 2, 3, 4, 5, 6, 7)

    /** Frames the rack can hold. */
    public val RACK_CAPACITY: Int = RACK_ROWS * RACK_COLUMN_Z.size

    /** Region half-extent around the island centre; the whole island deck is inside. */
    public const val REGION_HALF: Int = 27

    /** Height above the surface included in the region (projectiles in flight). */
    public const val REGION_HEIGHT: Int = 48

    /** Minecraft yaw: 0 = +z (south), 90 = -x (west), 270 = +x (east). */
    private const val FACE_EAST: Float = 270f
    private const val FACE_WEST: Float = 90f

    private val PIT_DUMMY_OFFSETS: List<Pair<Int, Int>> = listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2)

    public fun plan(centerX: Int, centerZ: Int, surfaceY: Int): LobbyTrainingPlan {
        val ix = centerX + ISLAND_X
        val iz = centerZ + ISLAND_Z
        val y = surfaceY + 1.0
        val dummies = ArrayList<TrainingDummySpot>()
        RANGE_DISTANCES.forEachIndexed { i, d ->
            dummies += TrainingDummySpot(
                id = "range-$d",
                kind = TrainingDummyKind.RANGE,
                x = ix + SHOOT_X + d + 0.5,
                y = y,
                z = iz + LANE_Z[i] + 0.5,
                yaw = FACE_WEST,
                distance = d,
            )
        }
        STAFF_DISTANCES.forEachIndexed { i, d ->
            dummies += TrainingDummySpot(
                id = "staff-$d",
                kind = TrainingDummyKind.RANGE,
                x = ix + STAFF_STAND_X + d + 0.5,
                y = y,
                z = iz + STAFF_LANE_Z[i] + 0.5,
                yaw = FACE_WEST,
                distance = d,
            )
        }
        PIT_DUMMY_OFFSETS.forEachIndexed { n, (dx, dz) ->
            dummies += TrainingDummySpot(
                id = "melee-${n + 1}",
                kind = TrainingDummyKind.MELEE,
                x = ix + PIT_X + dx + 0.5,
                y = surfaceY.toDouble(), // the pit floor is one block below the deck
                z = iz + PIT_Z + dz + 0.5,
                yaw = 0f,
            )
        }
        dummies += TrainingDummySpot(
            id = "mover-1",
            kind = TrainingDummyKind.MOVING,
            x = ix + TRACK_FROM_X + 0.5,
            y = y,
            z = iz + TRACK_Z + 0.5,
            yaw = 0f,
            track = TrainingTrack(
                fromX = ix + TRACK_FROM_X + 0.5,
                fromZ = iz + TRACK_Z + 0.5,
                toX = ix + TRACK_TO_X + 0.5,
                toZ = iz + TRACK_Z + 0.5,
                blocksPerTick = TRACK_SPEED,
            ),
        )
        val slots = ArrayList<TrainingRackSlot>()
        for (row in 0 until RACK_ROWS) {
            RACK_COLUMN_Z.forEachIndexed { col, dz ->
                slots += TrainingRackSlot(
                    index = row * RACK_COLUMN_Z.size + col,
                    x = ix + RACK_WALL_X + 1,
                    y = surfaceY + 1 + row,
                    z = iz + dz,
                    facing = "east",
                )
            }
        }
        val region = LobbyRegion(
            minX = ix - REGION_HALF.toDouble(),
            minY = surfaceY - 6.0,
            minZ = iz - REGION_HALF.toDouble(),
            maxX = ix + REGION_HALF.toDouble() + 1,
            maxY = surfaceY + REGION_HEIGHT.toDouble(),
            maxZ = iz + REGION_HALF.toDouble() + 1,
        )
        return LobbyTrainingPlan(
            region = region,
            spawn = Vec3(ix + SHOOT_X + 0.5, y, iz - 5 + 0.5),
            spawnYaw = FACE_EAST,
            dummies = dummies,
            rackSlots = slots,
        )
    }
}
