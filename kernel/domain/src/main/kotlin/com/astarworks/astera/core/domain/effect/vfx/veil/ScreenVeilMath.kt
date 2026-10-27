package com.astarworks.astera.core.domain.effect.vfx.veil

import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * ADR-0289 screen veil geometry: a square sprite quad [distance] blocks in front of the eye covers a bounded fraction of
 * the screen. Vertical FOV is fixed at [FOV_DEGREES] (the shipped client default) and the aspect at 16:9.
 */
public object ScreenVeilMath {
    public const val FOV_DEGREES: Double = 70.0
    public const val ASPECT: Double = 16.0 / 9.0
    public const val DEFAULT_MAX_FRACTION: Double = 0.15
    public const val HARD_MAX_FRACTION: Double = 0.18

    /** Visible height of the screen plane at [distance] blocks. */
    public fun viewHeight(distance: Double): Double = 2.0 * distance * tan(Math.toRadians(FOV_DEGREES / 2.0))

    /** Fraction of the screen area covered by a square of side [side] blocks at [distance]. */
    public fun screenFraction(side: Double, distance: Double): Double {
        val h = viewHeight(distance)
        return min(1.0, side * side / (h * h * ASPECT))
    }

    /** The largest square side allowed at [distance] for [maxFraction] (clamped to [HARD_MAX_FRACTION]). */
    public fun maxSide(distance: Double, maxFraction: Double): Double {
        val f = maxFraction.coerceIn(0.0, HARD_MAX_FRACTION)
        val h = viewHeight(distance)
        return sqrt(f * h * h * ASPECT)
    }

    /** [requested] side clamped so the projected fraction stays within [maxFraction] (and the hard max). */
    public fun clampSide(requested: Double, distance: Double, maxFraction: Double): Double =
        min(requested, maxSide(distance, maxFraction))
}
