package com.astarworks.astera.bots

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Ray march through the blocks the bot knows ([TerrainView]) from an eye point to a target point: the answer to "could
 * I shoot that player" without a server raycast. Pure and deterministic. A block of an unknown chunk (`null`) does not
 * block, so a bot that has not received a chunk yet does not believe the whole world is walled off.
 */
object LineOfSight {
    /** Sample spacing along the ray (blocks); well below one block so thin walls and corners are not skipped. */
    private const val STEP = 0.25

    /** The ray stops this far short of the target so the block the target stands in never counts as a wall. */
    private const val STOP_SHORT = 0.6

    fun clear(terrain: TerrainView, ex: Double, ey: Double, ez: Double, tx: Double, ty: Double, tz: Double): Boolean {
        val dx = tx - ex
        val dy = ty - ey
        val dz = tz - ez
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len <= STOP_SHORT) return true
        val steps = ceil((len - STOP_SHORT) / STEP).toInt()
        for (i in 1..steps) {
            val t = (i * STEP).coerceAtMost(len - STOP_SHORT) / len
            val bx = floor(ex + dx * t).toInt()
            val by = floor(ey + dy * t).toInt()
            val bz = floor(ez + dz * t).toInt()
            if (terrain.isSolid(bx, by, bz) == true) return false
        }
        return true
    }
}
