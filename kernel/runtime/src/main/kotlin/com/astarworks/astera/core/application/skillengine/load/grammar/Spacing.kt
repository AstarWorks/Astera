package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.expr.cel.EaseCurves
import kotlin.math.floor

/**
 * Spacing of an `every: {from, to, ease}` repeat (ADR-0303 3.7.1). The fire ticks are computed statically, once per op:
 * `t0 = at`, `t(k+1) = t(k) + max(1, round_half_up(lerp(from, to, E((t(k) - at) / for))))`, listed while `t(k) < at + for`
 * and while fewer than `times` ticks are listed. A plain `every: M` is not this path (exact multiples, 2.0).
 */
public object Spacing {
    /** A resolved `every: {from, to, ease}`: the period goes from [from] to [to] ticks over the op's window. */
    public data class Range(val from: Double, val to: Double, val ease: String)

    /** True when [ease] is one of the ADR-0303 3.7 names. The grammar rejects any other spelling at load. */
    public fun isKnownEase(ease: String): Boolean = EaseCurves.parse(ease) != null

    /**
     * Fire ticks of one eased repeat. [forTicks] is the finite window (>= 1), [times] caps the count when given (>= 1).
     * The caller validates [Range.ease] with [isKnownEase] at load; an unknown name here is a programming error.
     */
    public fun fireTicks(at: Int, every: Range, forTicks: Int, times: Int?): List<Int> {
        require(forTicks >= 1) { "spacing needs a finite window of at least 1 tick, got $forTicks" }
        require(times == null || times >= 1) { "times must be >= 1, got $times" }
        val curve = requireNotNull(EaseCurves.parse(every.ease)) { "unknown ease '${every.ease}' (validate with isKnownEase)" }
        val end = at + forTicks
        val out = ArrayList<Int>()
        var t = at
        while (t < end && (times == null || out.size < times)) {
            out += t
            val e = curve((t - at).toDouble() / forTicks)
            val period = floor(every.from + (every.to - every.from) * e + 0.5).toInt()
            t += period.coerceAtLeast(1)
        }
        return out
    }
}
