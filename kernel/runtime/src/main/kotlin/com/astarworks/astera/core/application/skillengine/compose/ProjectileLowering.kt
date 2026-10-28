package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import kotlin.math.ceil
import kotlin.math.max

/** Lowers the handler list of one projectile event (ADR-0303 3.6 `on:`). Rows come back relative to the event tick 0. */
public fun interface HandlerLowering {
    public fun lower(nodes: List<OpNode>, lc: LowerContext): Result<Lowered, SkillDecodeError>
}

/**
 * Pure kinematics of a projectile body (ADR-0303 3.6, 3.7.1). Nothing here knows the YAML or the table: the speed ramp integrated
 * exactly and the end window `[min, max]`. The body's `motion:` laws are composed into its riders (AnchorScope.laws), not into rows here.
 */
public object ProjectileKinematics {
    /** Speed in blocks per tick: `from` -> `to` over [rampTicks] ticks shaped by [ease], then `to` for ever. Constant when [rampTicks] is 0. */
    public data class Speed(val from: Double, val to: Double, val rampTicks: Int, val ease: Easing) {
        init {
            require(rampTicks >= 0) { "negative ramp" }
            require(rampTicks > 0 || from == to) { "a constant speed has equal ends" }
        }

        public fun at(t: Double): Double =
            if (rampTicks == 0) to else from + (to - from) * ease.apply(minOf(t, rampTicks.toDouble()) / rampTicks)
    }

    public val linear: Easing = Easing.fromKey("linear") ?: error("Easing has no linear")

    public fun constant(speed: Double): Speed = Speed(speed, speed, 0, linear)

    // 5-point Gauss-Legendre on [-1, 1].
    private val nodes = doubleArrayOf(0.0, 0.5384693101056831, -0.5384693101056831, 0.9061798459386640, -0.9061798459386640)
    private val weights = doubleArrayOf(0.5688888888888889, 0.4786286704993665, 0.4786286704993665, 0.2369268850561891, 0.2369268850561891)
    private const val SLICE = 1.0 / 16.0

    /** The exact path length `integral of speed(t) dt` from 0 to [ticks] (Gauss-Legendre on slices of 1/16 tick, split at the ramp end). */
    public fun pathLength(speed: Speed, ticks: Double): Double {
        if (ticks <= 0.0) return 0.0
        if (speed.rampTicks == 0) return speed.to * ticks
        val ramp = speed.rampTicks.toDouble()
        val inRamp = integrate(speed, 0.0, minOf(ticks, ramp))
        return if (ticks <= ramp) inRamp else inRamp + speed.to * (ticks - ramp)
    }

    private fun integrate(speed: Speed, a: Double, b: Double): Double {
        val n = max(1, ceil((b - a) / SLICE).toInt())
        val h = (b - a) / n
        var sum = 0.0
        for (i in 0 until n) {
            val lo = a + i * h
            val mid = lo + h / 2
            var s = 0.0
            for (k in nodes.indices) s += weights[k] * speed.at(mid + nodes[k] * h / 2)
            sum += s * h / 2
        }
        return sum
    }

    /** First whole tick k (1..[cap]) whose path length reaches [range]; null when the body never does (speed 0, cap too small). */
    public fun ticksToRange(speed: Speed, range: Double, cap: Int): Int? {
        for (k in 1..cap) if (pathLength(speed, k.toDouble()) >= range - EPS) return k
        return null
    }

    private const val EPS = 1e-9

    /**
     * The end of flight: the earlier of the `for` ticks and the tick the path reaches `range`. [stickTicks] is added for mode `stick`
     * (the body is stuck that long before `end`). Min is 1 (a hit or a block on the first tick), max is the printed `T+max`.
     */
    public fun endWindow(flightTicks: Int, stickTicks: Int): TickRange = TickRange(1, flightTicks + stickTicks)
}
