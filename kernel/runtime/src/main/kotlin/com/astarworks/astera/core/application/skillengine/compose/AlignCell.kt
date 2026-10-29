package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.domain.skillengine.math.Quaterniond

/**
 * A live `align` rotation cell with the numeric turns that ride on it: `align:<axis>><to>^<up>[@<w>][#x,y,z,w]`. The aligned rotation owns the
 * rotation channel, so a turn listed after the align (a spin about the aligned axis, a rider's own tilt) cannot be a second key; it is the
 * quaternion after `#`, applied on the right of the aligned rotation at play time.
 */
internal object AlignCell {
    fun isAlign(cell: String): Boolean = cell.startsWith("align:")

    /** The cell without the `#` suffix. */
    fun bare(cell: String): String = cell.substringBefore('#')

    /** The numeric turn after the align, or null. */
    fun post(cell: String): Quaterniond? {
        if (!isAlign(cell) || '#' !in cell) return null
        val v = cell.substringAfter('#').split(',').map { it.toDouble() }
        return Quaterniond(v[0], v[1], v[2], v[3])
    }

    /** [cell] with [turn] composed after the turn it already carries; an identity [turn] returns [cell] unchanged. */
    fun withPost(cell: String, turn: Quaterniond): String {
        if (!isAlign(cell)) return cell
        val q = (post(cell) ?: Quaterniond()).mul(turn)
        if (kotlin.math.abs(q.w) > 1.0 - 1e-12) return bare(cell)
        return "${bare(cell)}#${q.x},${q.y},${q.z},${q.w}"
    }
}
