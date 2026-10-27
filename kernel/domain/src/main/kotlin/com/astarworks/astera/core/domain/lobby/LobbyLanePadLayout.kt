package com.astarworks.astera.core.domain.lobby

import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where the queue lane pads sit on the lobby's queue island (ADR-0180, F3-1). A pure function
 * of the lane count, shared by [LobbyIslandGenerator] (which builds the pads) and
 * [com.astarworks.astera.core.application.service.lobby.LobbyLanePadService] (which detects a player stepping on one), so the two can never drift
 * apart and no lane list is hard-coded.
 *
 * Slots fill in this order: front row (3 pads, boards on the south side facing north), back row
 * (2 pads either side of the north bridge, boards facing south), then further front rows of 3,
 * each 9 blocks further south. Slot offsets are relative to the queue anchor
 * `(centerX, centerZ + 50)`; extra front rows move the island centre south and grow it.
 */
public object LobbyLanePadLayout {

    /** A pad on the queue island. [dx]/[dz] are relative to the queue anchor. */
    public data class Slot(val index: Int, val dx: Int, val dz: Int, val boardFacesNorth: Boolean)

    /** Horizontal distance from the pad centre that counts as "standing on the pad". */
    public const val TRIGGER_RADIUS: Double = 1.9

    /** Queue anchor offset from the plaza centre (south). */
    public const val ANCHOR_OFFSET_Z: Int = 50

    private const val FRONT_PER_ROW = 3
    private const val ROW_STEP = 9
    private val FRONT_X = intArrayOf(-7, 0, 7)
    private val BACK_X = intArrayOf(-7, 7)

    public fun slots(laneCount: Int): List<Slot> {
        require(laneCount >= 0) { "laneCount must be >= 0" }
        return (0 until laneCount).map { i ->
            when {
                i < FRONT_PER_ROW -> Slot(i, FRONT_X[i], 4, boardFacesNorth = true)
                i < FRONT_PER_ROW + BACK_X.size -> Slot(i, BACK_X[i - FRONT_PER_ROW], -6, boardFacesNorth = false)
                else -> {
                    val j = i - FRONT_PER_ROW - BACK_X.size
                    Slot(i, FRONT_X[j % FRONT_PER_ROW], 4 + ROW_STEP * (1 + j / FRONT_PER_ROW), boardFacesNorth = true)
                }
            }
        }
    }

    /** Front rows beyond the first one. */
    public fun extraRows(laneCount: Int): Int {
        val overflow = laneCount - FRONT_PER_ROW - BACK_X.size
        return if (overflow <= 0) 0 else (overflow + FRONT_PER_ROW - 1) / FRONT_PER_ROW
    }

    /** Island centre Z offset from the plaza centre. */
    public fun islandOffsetZ(laneCount: Int): Int = ANCHOR_OFFSET_Z + 5 * extraRows(laneCount)

    /** Nominal island radius. */
    public fun islandRadius(laneCount: Int): Double = 17.0 + 7 * extraRows(laneCount)

    /** Pad centre (standing position, block centre) per lane id, in world coordinates. */
    public fun padCenters(lanes: List<String>, centerX: Int, centerZ: Int, surfaceY: Int): Map<String, Vec3> {
        val out = LinkedHashMap<String, Vec3>()
        slots(lanes.size).forEachIndexed { i, slot ->
            out[lanes[i]] = Vec3(
                centerX + slot.dx + 0.5,
                surfaceY + 1.0,
                centerZ + ANCHOR_OFFSET_Z + slot.dz + 0.5,
            )
        }
        return out
    }

    /** Human display form of a lane id (`carry-tnt` -> `CARRY TNT`), used on signs and holograms. */
    public fun displayName(laneId: String): String = laneId.replace('-', ' ').uppercase()

    /** The lane whose pad contains the feet position, or null. */
    public fun laneAt(pads: Map<String, Vec3>, surfaceY: Int, at: Vec3): String? {
        if (floor(at.y).toInt() !in surfaceY..surfaceY + 2) return null
        return pads.entries.firstOrNull { (_, c) -> hypot(at.x - c.x, at.z - c.z) <= TRIGGER_RADIUS }?.key
    }
}
