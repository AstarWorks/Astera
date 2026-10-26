package com.astarworks.astera.core.domain.effect.vfx

import com.astarworks.astera.core.domain.animation.Easing

/** One key of a [VfxCurve]: value [v] reached at normalised time [t] (0..1), eased by [ease] on the way in. */
public data class VfxCurveKey<T>(
    public val t: Double,
    public val v: T,
    /** Applies to the segment ENDING at this key (same convention as element keyframes). */
    public val ease: Easing = Easing.LINEAR,
)

/**
 * Multi-key curve over a normalised lifetime: any number of keys (>= 1), `t` strictly ascending in 0..1.
 * Before the first key / after the last key the value is held.
 */
public data class VfxCurve<T>(public val keys: List<VfxCurveKey<T>>) {
    init {
        require(keys.isNotEmpty()) { "curve needs at least 1 key" }
        require(keys.all { it.t in 0.0..1.0 }) { "curve key t must be 0..1" }
        keys.zipWithNext().forEach { (a, b) -> require(b.t > a.t) { "curve key t must be strictly ascending (${a.t} then ${b.t})" } }
    }

    /** Value at [f] (0..1) using [lerp] between neighbouring keys. */
    public fun sample(f: Double, lerp: (T, T, Double) -> T): T {
        val x = f.coerceIn(0.0, 1.0)
        if (x <= keys.first().t) return keys.first().v
        if (x >= keys.last().t) return keys.last().v
        val k = keys.indexOfFirst { it.t >= x }
        val a = keys[k - 1]
        val b = keys[k]
        return lerp(a.v, b.v, b.ease.apply((x - a.t) / (b.t - a.t)))
    }

    public companion object {
        public fun <T> constant(v: T): VfxCurve<T> = VfxCurve(listOf(VfxCurveKey(0.0, v)))

        public val LERP_DOUBLE: (Double, Double, Double) -> Double = { a, b, f -> a + (b - a) * f }
        public val LERP_RGB: (Int, Int, Double) -> Int = { a, b, f -> VfxQuad.lerpRgb(a, b, f) }
    }
}

/** Sample a numeric curve. */
public fun VfxCurve<Double>.at(f: Double): Double = sample(f, VfxCurve.LERP_DOUBLE)

/** Sample an `0xRRGGBB` colour curve. */
public fun VfxCurve<Int>.rgbAt(f: Double): Int = sample(f, VfxCurve.LERP_RGB)
