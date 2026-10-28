package com.astarworks.astera.core.domain.effect

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * `screen_veil` geometry (v1 ScreenVeilMath, ADR-0291): a square sprite quad [distance] blocks in front of the eye covers a
 * bounded fraction of the screen. Vertical FOV is fixed at [FOV_DEGREES] (the shipped client default), the aspect at 16:9.
 */
public object ScreenVeilMath {
    public const val FOV_DEGREES: Double = 70.0
    public const val ASPECT: Double = 16.0 / 9.0
    public const val HARD_MAX_FRACTION: Double = 0.18

    /** Side in blocks of a sprite at `scale: 1` (the 8 px glyph cell). */
    public const val SPRITE_BASE_SIDE: Double = 0.2
    public const val MIN_DISTANCE: Double = 0.3
    public const val MAX_DISTANCE: Double = 1.0

    /** Visible height of the screen plane at [distance] blocks. */
    public fun viewHeight(distance: Double): Double = 2.0 * distance * tan(Math.toRadians(FOV_DEGREES / 2.0))

    /** The largest square side allowed at [distance] for [maxFraction] (clamped to [HARD_MAX_FRACTION]). */
    public fun maxSide(distance: Double, maxFraction: Double): Double {
        val h = viewHeight(distance)
        return sqrt(maxFraction.coerceIn(0.0, HARD_MAX_FRACTION) * h * h * ASPECT)
    }

    /** [requested] side clamped so the projected fraction stays within [maxFraction] (and the hard max). */
    public fun clampSide(requested: Double, distance: Double, maxFraction: Double): Double =
        min(requested, maxSide(distance, maxFraction))
}
