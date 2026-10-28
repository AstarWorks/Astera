package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.application.skillengine.load.spec.OpNode
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.TickRange
import com.astarworks.astera.core.domain.skillengine.table.Val
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Lowers the handler list of one projectile event (ADR-0303 3.6 `on:`). Rows come back relative to the event tick 0. */
public fun interface HandlerLowering {
    public fun lower(nodes: List<OpNode>, lc: LowerContext): Result<Lowered, SkillDecodeError>
}

/**
 * Pure kinematics of a projectile body (ADR-0303 3.6, 3.7.1). Nothing here knows the YAML or the table: the speed ramp integrated
 * exactly, the end window `[min, max]`, and the body's `motion:` keys turned into `key` rows.
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

    /**
     * Turns the lowered `motion:` keys of the body into `key` rows (one segment write per boundary, ADR-0303 4): the boundaries are the key
     * ticks; a segment whose channel carries a non-linear ease is sampled every tick. Offsets and scales lerp, turns slerp (rotation vector in
     * degrees, 3). A pose other than the neutral one at the first key is written first with duration 0. No `LawSegmenter` is used here.
     */
    internal fun motionRows(keys: List<TrackKey>, body: InstanceId, src: SrcPath): List<Row> {
        if (keys.isEmpty()) return emptyList()
        val offsets = channel(keys, { it.offset }, { it }) { a, b, u -> Vec3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u) }
        val scales = channel(keys, { it.scale }, { it }) { a, b, u -> Vec3(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u, a.z + (b.z - a.z) * u) }
        val turns = channel(keys, { it.turn }, { turnQuat(it) }) { a, b, u -> Quaterniond(a).slerp(b, u) }
        val liveOffset = keys.firstNotNullOfOrNull { k -> k.offset?.takeIf { it !is Value.Literal } }
        val liveScale = keys.firstNotNullOfOrNull { k -> k.scale?.takeIf { it !is Value.Literal } }
        val liveTurn = keys.firstNotNullOfOrNull { k -> k.turn?.takeIf { it !is Value.Literal } }

        val boundaries = sortedSetOf<Int>()
        keys.forEach { boundaries += it.tick }
        val base = boundaries.toList()
        for ((p, q) in base.zipWithNext()) {
            if (q - p > 1 && listOf(offsets, scales, turns).any { it.easedBetween(p, q) }) for (t in p + 1 until q) boundaries += t
        }
        val ticks = boundaries.toList()

        fun write(at: Int, duration: Int, t: Int): Row = Row(
            at, body,
            Action.Key(
                body, duration,
                liveOffset?.let(::live3) ?: Val.Known(offsets.at(t, Vector3d(0.0, 0.0, 0.0)) { Vector3d(it.x, it.y, it.z) }),
                liveTurn?.let { Val.Live("motion.turn", null) } ?: Val.Known(turns.at(t, Quaterniond()) { Quaterniond(it) }),
                liveScale?.let(::live3) ?: Val.Known(scales.at(t, Vector3d(1.0, 1.0, 1.0)) { Vector3d(it.x, it.y, it.z) }),
            ),
            src,
        )

        val rows = ArrayList<Row>()
        val first = ticks.first()
        val neutral = offsets.at(first, Vec3(0.0, 0.0, 0.0)) { it } == Vec3(0.0, 0.0, 0.0) &&
            scales.at(first, Vec3(1.0, 1.0, 1.0)) { it } == Vec3(1.0, 1.0, 1.0) &&
            turns.at(first, Quaterniond()) { it }.let { abs(it.w) > 1.0 - 1e-12 }
        if (!neutral) rows += write(first, 0, first)
        for ((p, q) in ticks.zipWithNext()) rows += write(p, q - p, q)
        return rows
    }

    private fun live3(v: Value<Vec3>): Val<Vector3d> = Val.Live(
        when (v) {
            is Value.ParamRef -> "param:${v.name}"
            is Value.Expr -> "expr:${v.source}"
            is Value.Literal -> "literal"
        },
        null,
    )

    private fun turnQuat(rv: Vec3): Quaterniond {
        val len = rv.length()
        if (len < 1e-12) return Quaterniond()
        return Quaterniond().setAngleAxis(Math.toRadians(len), rv.x / len, rv.y / len, rv.z / len)
    }

    /** One channel (offset / turn / scale) of the key table: the keyed points, in tick order. */
    private class Channel<V>(val points: List<Point<V>>, val lerp: (V, V, Double) -> V) {
        class Point<V>(val tick: Int, val value: V, val ease: Easing?, val step: Boolean)

        /** The value at [t]: held before the first and after the last key, interpolated between (ease of the key ENDING the segment). */
        fun <R> at(t: Int, neutral: R, convert: (V) -> R): R {
            if (points.isEmpty()) return neutral
            val first = points.first()
            if (t <= first.tick) return convert(first.value)
            val last = points.last()
            if (t >= last.tick) return convert(last.value)
            val hi = points.indexOfFirst { it.tick >= t }
            val b = points[hi]
            val a = points[hi - 1]
            if (b.tick == t) return convert(b.value)
            if (b.step) return convert(a.value)
            val u = (t - a.tick).toDouble() / (b.tick - a.tick)
            return convert(lerp(a.value, b.value, (b.ease ?: Easing.fromKey("linear")!!).apply(u)))
        }

        fun easedBetween(p: Int, q: Int): Boolean {
            val b = points.firstOrNull { it.tick >= q } ?: return false
            val a = points.lastOrNull { it.tick <= p } ?: return false
            return a !== b && a.tick <= p && b.tick >= q && (b.step || (b.ease != null && b.ease !== Easing.fromKey("linear")))
        }
    }

    private fun <V> channel(
        keys: List<TrackKey>,
        pick: (TrackKey) -> Value<Vec3>?,
        conv: (Vec3) -> V,
        lerp: (V, V, Double) -> V,
    ): Channel<V> {
        val pts = keys.mapNotNull { k ->
            val v = pick(k) as? Value.Literal ?: return@mapNotNull null
            Channel.Point(k.tick, conv(v.value), k.ease?.let { Easing.fromKey(it) }, k.ease == "step")
        }.sortedBy { it.tick }
        return Channel(pts, lerp)
    }
}
