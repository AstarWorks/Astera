package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * The lobby tool test bay (MAP-A6): a walled yard east of the weapon test range where tools and tool skills
 * can be tried on stone-, dirt- and wood-type blocks. Everything inside [region] may be broken (by hand or by
 * skills) and is restored by the bay service; everything else in the lobby stays protected.
 *
 * [rackSlots] are the nine tool frames (rows pickaxe / shovel / axe, columns stone / iron / diamond, index =
 * row * 3 + column). [resetButton] is the wall button that restores the bay at once.
 */
public data class LobbyToolBayPlan(
    val region: LobbyRegion,
    /** The whole walled yard (rack and button included); standing in it lets a player claim from the tool rack. */
    val area: LobbyRegion,
    val rackSlots: List<TrainingRackSlot>,
    val resetButton: LobbyBlockPos,
    val entry: Vec3,
    val idleResetTicks: Long,
)

/** Geometry of the tool bay; offsets are relative to the training island centre. +x east, +z south. */
public object LobbyToolBayLayout {
    /** Wall with the rack on its east face. */
    public const val WALL_X: Int = 24
    public const val MAX_X: Int = 32
    public const val MIN_Z: Int = -6
    public const val MAX_Z: Int = 6

    /** Doorway in the west wall (z offsets, inclusive). */
    public const val GATE_Z1: Int = 3
    public const val GATE_Z2: Int = 4

    /** Rack: frames one cell east of the wall, columns at these z offsets. */
    public val RACK_Z: List<Int> = listOf(-2, -1, 0)
    public const val RACK_ROWS: Int = 3
    public const val RESET_Z: Int = 1

    /** Editable box starts one cell east of the rack so frames and the button are never part of it. */
    public const val REGION_MIN_X: Int = 26
    public const val REGION_HEIGHT: Int = 13

    /** Blocks restore after this many idle ticks (about 10 s). */
    public const val IDLE_RESET_TICKS: Long = 200

    public fun plan(centerX: Int, centerZ: Int, surfaceY: Int): LobbyToolBayPlan {
        val ix = centerX + LobbyTrainingLayout.ISLAND_X
        val iz = centerZ + LobbyTrainingLayout.ISLAND_Z
        val slots = ArrayList<TrainingRackSlot>()
        for (row in 0 until RACK_ROWS) {
            RACK_Z.forEachIndexed { col, dz ->
                slots += TrainingRackSlot(
                    index = row * RACK_Z.size + col,
                    x = ix + WALL_X + 1,
                    y = surfaceY + 4 - row,
                    z = iz + dz,
                    facing = "east",
                )
            }
        }
        return LobbyToolBayPlan(
            region = LobbyRegion(
                (ix + REGION_MIN_X).toDouble(), (surfaceY + 1).toDouble(), (iz + MIN_Z).toDouble(),
                (ix + MAX_X + 1).toDouble(), (surfaceY + 1 + REGION_HEIGHT).toDouble(), (iz + MAX_Z + 1).toDouble(),
            ),
            area = LobbyRegion(
                (ix + WALL_X).toDouble(), surfaceY.toDouble(), (iz + MIN_Z - 1).toDouble(),
                (ix + MAX_X + 2).toDouble(), (surfaceY + 1 + REGION_HEIGHT).toDouble(), (iz + MAX_Z + 2).toDouble(),
            ),
            rackSlots = slots,
            resetButton = LobbyBlockPos(ix + WALL_X + 1, surfaceY + 2, iz + RESET_Z),
            entry = Vec3(ix + WALL_X - 0.5, surfaceY + 1.0, iz + GATE_Z1 + 1.0),
            idleResetTicks = IDLE_RESET_TICKS,
        )
    }
}
