package com.astarworks.astera.core.application.skillengine.expr.cel

import com.astarworks.astera.core.domain.animation.Easing
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * Easing names of ADR-0303 3.7: `linear`, `in_|out_|in_out_` + sine/quad/cubic/quart/quint/expo/circ/back/elastic/bounce,
 * `cubic_bezier(x1, y1, x2, y2)` and `steps(n)`. [parse] returns null for any other spelling, so a typo is a load error.
 */
internal object EaseCurves {
    private val cache = ConcurrentHashMap<String, (Double) -> Double>()
    private val BEZIER = Regex("""cubic_bezier\(\s*([^,\s]+)\s*,\s*([^,\s]+)\s*,\s*([^,\s]+)\s*,\s*([^,\s)]+)\s*\)""")
    private val STEPS = Regex("""steps\(\s*(\d+)\s*\)""")

    /** `steps(n)` spelling; a stepped curve is a pose cut, which a motion law cannot carry. */
    fun isSteps(name: String): Boolean = STEPS.matches(name.trim())

    fun parse(name: String): ((Double) -> Double)? {
        cache[name]?.let { return it }
        val curve = build(name.trim()) ?: return null
        cache[name] = curve
        return curve
    }

    private fun build(n: String): ((Double) -> Double)? {
        STEPS.matchEntire(n)?.let { m ->
            val steps = m.groupValues[1].toInt()
            if (steps < 1) return null
            return { t -> if (t >= 1.0) 1.0 else floor(t.coerceAtLeast(0.0) * steps) / steps }
        }
        BEZIER.matchEntire(n)?.let { m ->
            val v = m.groupValues.drop(1).map { it.toDoubleOrNull() ?: return null }
            if (v[0] !in 0.0..1.0 || v[2] !in 0.0..1.0) return null
            return bezier(v[0], v[1], v[2], v[3])
        }
        val e = Easing.fromKey(n) ?: return null
        if (e == Easing.STEP) return null
        // fromKey is lenient (case, '-', ease_ prefix); the spec spelling is exact
        val canonical = if (e == Easing.LINEAR) "linear" else e.name.removePrefix("EASE_").lowercase()
        if (canonical != n) return null
        return e::apply
    }

    /** CSS cubic-bezier: solve x(s) = t by bisection, return y(s). */
    private fun bezier(x1: Double, y1: Double, x2: Double, y2: Double): (Double) -> Double {
        fun coord(a: Double, b: Double, s: Double): Double {
            val u = 1 - s
            return 3 * u * u * s * a + 3 * u * s * s * b + s * s * s
        }
        return { t ->
            val c = t.coerceIn(0.0, 1.0)
            var lo = 0.0
            var hi = 1.0
            repeat(40) {
                val mid = (lo + hi) / 2
                if (coord(x1, x2, mid) < c) lo = mid else hi = mid
            }
            coord(y1, y2, (lo + hi) / 2)
        }
    }
}
