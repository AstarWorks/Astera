package com.astarworks.astera.domain.animation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Standard easing functions for skill / display animations.
 *
 * Ports the *enum* shape used by RTM (`EasingFunction`) and adds the math.
 * Pure Kotlin — no JOML, no Bukkit. Coordinates are domain-defined, animation
 * curves are too.
 *
 * Each function maps `t ∈ [0, 1]` (normalized time) to a progress value
 * (usually also in `[0, 1]`, but `BACK` and `ELASTIC` overshoot intentionally).
 * Call [apply] for the raw curve or [interpolate] to lerp between two values.
 *
 * Reference: https://easings.net/
 */
enum class Easing(private val fn: (Double) -> Double) {
    LINEAR({ t -> t }),

    EASE_IN_SINE({ t -> 1.0 - cos(t * PI / 2.0) }),
    EASE_OUT_SINE({ t -> sin(t * PI / 2.0) }),
    EASE_IN_OUT_SINE({ t -> -(cos(PI * t) - 1.0) / 2.0 }),

    EASE_IN_QUAD({ t -> t * t }),
    EASE_OUT_QUAD({ t -> 1.0 - (1.0 - t).pow(2) }),
    EASE_IN_OUT_QUAD({ t ->
        if (t < 0.5) 2.0 * t * t else 1.0 - (-2.0 * t + 2.0).pow(2) / 2.0
    }),

    EASE_IN_CUBIC({ t -> t * t * t }),
    EASE_OUT_CUBIC({ t -> 1.0 - (1.0 - t).pow(3) }),
    EASE_IN_OUT_CUBIC({ t ->
        if (t < 0.5) 4.0 * t * t * t else 1.0 - (-2.0 * t + 2.0).pow(3) / 2.0
    }),

    EASE_IN_QUART({ t -> t.pow(4) }),
    EASE_OUT_QUART({ t -> 1.0 - (1.0 - t).pow(4) }),
    EASE_IN_OUT_QUART({ t ->
        if (t < 0.5) 8.0 * t.pow(4) else 1.0 - (-2.0 * t + 2.0).pow(4) / 2.0
    }),

    EASE_IN_QUINT({ t -> t.pow(5) }),
    EASE_OUT_QUINT({ t -> 1.0 - (1.0 - t).pow(5) }),
    EASE_IN_OUT_QUINT({ t ->
        if (t < 0.5) 16.0 * t.pow(5) else 1.0 - (-2.0 * t + 2.0).pow(5) / 2.0
    }),

    EASE_IN_EXPO({ t -> if (t == 0.0) 0.0 else 2.0.pow(10.0 * t - 10.0) }),
    EASE_OUT_EXPO({ t -> if (t == 1.0) 1.0 else 1.0 - 2.0.pow(-10.0 * t) }),
    EASE_IN_OUT_EXPO({ t ->
        when {
            t == 0.0 -> 0.0
            t == 1.0 -> 1.0
            t < 0.5 -> 2.0.pow(20.0 * t - 10.0) / 2.0
            else -> (2.0 - 2.0.pow(-20.0 * t + 10.0)) / 2.0
        }
    }),

    EASE_IN_CIRC({ t -> 1.0 - sqrt(1.0 - t * t) }),
    EASE_OUT_CIRC({ t -> sqrt(1.0 - (t - 1.0).pow(2)) }),
    EASE_IN_OUT_CIRC({ t ->
        if (t < 0.5) (1.0 - sqrt(1.0 - (2.0 * t).pow(2))) / 2.0
        else (sqrt(1.0 - (-2.0 * t + 2.0).pow(2)) + 1.0) / 2.0
    }),

    EASE_IN_BACK({ t ->
        val c1 = 1.70158
        val c3 = c1 + 1.0
        c3 * t * t * t - c1 * t * t
    }),
    EASE_OUT_BACK({ t ->
        val c1 = 1.70158
        val c3 = c1 + 1.0
        1.0 + c3 * (t - 1.0).pow(3) + c1 * (t - 1.0).pow(2)
    }),
    EASE_IN_OUT_BACK({ t ->
        val c1 = 1.70158
        val c2 = c1 * 1.525
        if (t < 0.5)
            ((2.0 * t).pow(2) * ((c2 + 1.0) * 2.0 * t - c2)) / 2.0
        else
            ((2.0 * t - 2.0).pow(2) * ((c2 + 1.0) * (t * 2.0 - 2.0) + c2) + 2.0) / 2.0
    }),

    EASE_IN_ELASTIC({ t ->
        val c4 = 2.0 * PI / 3.0
        when (t) {
            0.0 -> 0.0
            1.0 -> 1.0
            else -> -(2.0.pow(10.0 * t - 10.0)) * sin((t * 10.0 - 10.75) * c4)
        }
    }),
    EASE_OUT_ELASTIC({ t ->
        val c4 = 2.0 * PI / 3.0
        when (t) {
            0.0 -> 0.0
            1.0 -> 1.0
            else -> 2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0
        }
    }),
    EASE_IN_OUT_ELASTIC({ t ->
        val c5 = 2.0 * PI / 4.5
        when {
            t == 0.0 -> 0.0
            t == 1.0 -> 1.0
            t < 0.5 -> -(2.0.pow(20.0 * t - 10.0) * sin((20.0 * t - 11.125) * c5)) / 2.0
            else -> (2.0.pow(-20.0 * t + 10.0) * sin((20.0 * t - 11.125) * c5)) / 2.0 + 1.0
        }
    }),

    EASE_OUT_BOUNCE({ t ->
        val n1 = 7.5625
        val d1 = 2.75
        when {
            t < 1.0 / d1 -> n1 * t * t
            t < 2.0 / d1 -> n1 * (t - 1.5 / d1).let { it * it } + 0.75
            t < 2.5 / d1 -> n1 * (t - 2.25 / d1).let { it * it } + 0.9375
            else -> n1 * (t - 2.625 / d1).let { it * it } + 0.984375
        }
    }),
    EASE_IN_BOUNCE({ t -> 1.0 - EASE_OUT_BOUNCE.apply(1.0 - t) }),
    EASE_IN_OUT_BOUNCE({ t ->
        if (t < 0.5) (1.0 - EASE_OUT_BOUNCE.apply(1.0 - 2.0 * t)) / 2.0
        else (1.0 + EASE_OUT_BOUNCE.apply(2.0 * t - 1.0)) / 2.0
    }),
    ;

    /** Apply the easing curve to a normalized time. Input is clamped to `[0, 1]`. */
    fun apply(t: Double): Double = fn(t.coerceIn(0.0, 1.0))

    /** Linear interpolation between [from] and [to] with this easing curve applied to `t`. */
    fun interpolate(from: Double, to: Double, t: Double): Double = from + (to - from) * apply(t)
}
