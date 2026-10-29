package com.astarworks.astera.core.domain.skillengine.motion

import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.TrackKey
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Lowers [RateLaw]s to the [TrackKey] table the presentation sampler already reads (ADR-0303 section 3, 3.7). The sampler is not touched:
 *
 * - It lerps (slerps for `turn`) between the two keys around a tick and applies the ease named on the key ENDING a segment to that segment's
 *   progress. An eased constant-rate law is therefore exactly ONE segment (two keys, `ease` on the end key): the ease shapes the segment total.
 * - A linear law is two keys. A spin whose total exceeds the slerp shortest-arc limit is cut into equal chunks of at most [MAX_SPIN_CHUNK_DEG].
 * - A law with a curved time profile that no single segment reproduces (`accel`, a fade exponent other than 1, an eased spin over the chunk
 *   limit) is sampled at every tick of its window: the sampler is only ever evaluated on integer ticks, so a key per tick is exact there.
 *
 * Laws on different channels (offset / turn / scale / alpha) combine by concatenation; the sampler filters keys per channel.
 */
public object RateLawExpander {
    /** A slerp segment must stay below 180 degrees (shortest arc); chunks stay clear of that limit. */
    public const val MAX_SPIN_CHUNK_DEG: Double = 170.0

    public fun expand(laws: List<RateLaw>): List<TrackKey> = laws.flatMap(::expand).sortedBy { it.tick }

    public fun expand(law: RateLaw): List<TrackKey> = when (law) {
        is MoveRate -> move(law)
        is SpinRate -> spin(law)
        is GrowRate -> grow(law)
        is FadeRate -> fade(law)
    }

    private fun lit(v: Vec3): Value<Vec3> = Value.Literal(v)
    private fun key(tick: Int, offset: Vec3? = null, turn: Vec3? = null, scale: Vec3? = null, alpha: Double? = null, ease: String? = null) =
        TrackKey(tick, offset?.let(::lit), turn?.let(::lit), scale?.let(::lit), ease, alpha?.let { Value.Literal(it) })

    private fun e(name: String?, u: Double): Double = name?.let { Easing.fromKey(it) }?.apply(u) ?: u

    private fun move(l: MoveRate): List<TrackKey> {
        if (l.drag != 0.0) {
            return (0..l.forTicks).map { tau -> key(l.from + tau, offset = l.start + l.velocity * dragFactor(l.drag, tau.toDouble())) }
        }
        if (l.accel.lengthSquared() != 0.0) {
            return (0..l.forTicks).map { tau ->
                val t = tau.toDouble()
                key(l.from + tau, offset = l.start + l.velocity * t + l.accel * (t * t / 2.0))
            }
        }
        val end = l.start + l.velocity * l.forTicks.toDouble()
        return listOf(key(l.from, offset = l.start), key(l.from + l.forTicks, offset = end, ease = l.ease))
    }

    private fun grow(l: GrowRate): List<TrackKey> {
        if (l.accel.lengthSquared() == 0.0) {
            val end = l.start + l.perTick * l.forTicks.toDouble()
            return listOf(key(l.from, scale = l.start), key(l.from + l.forTicks, scale = end, ease = l.ease))
        }
        return (0..l.forTicks).map { tau ->
            val t = tau.toDouble()
            key(l.from + tau, scale = l.start + l.perTick * t + l.accel * (t * t / 2.0))
        }
    }

    private fun fade(l: FadeRate): List<TrackKey> {
        if (l.exponent == 1.0) {
            return listOf(key(l.from, alpha = l.start), key(l.from + l.forTicks, alpha = l.to, ease = l.ease))
        }
        return (0..l.forTicks).map { tau ->
            val k = (1.0 - e(l.ease, tau.toDouble() / l.forTicks)).pow(l.exponent)
            key(l.from + tau, alpha = l.to + (l.start - l.to) * k)
        }
    }

    private fun spin(l: SpinRate): List<TrackKey> {
        val axis = l.axis.normalize()
        val zeroStart = l.start.lengthSquared() < 1e-18
        val q0 = Quaternion.fromRotationVector(l.start)
        fun vec(deg: Double): Vec3 =
            if (zeroStart) axis * deg else toRotationVector(Quaternion.axisAngle(axis, Math.toRadians(deg)) * q0)

        val total = l.degPerTick * l.forTicks
        if (l.drag != 0.0) {
            val angles = (0..l.forTicks).map { tau -> l.degPerTick * dragFactor(l.drag, tau.toDouble()) }
            angles.zipWithNext().forEach { (a, b) ->
                require(abs(b - a) < 180.0) { "rate law: spin turns ${abs(b - a)} degrees in one tick; slerp cannot carry that, split the law" }
            }
            return angles.mapIndexed { tau, deg -> key(l.from + tau, turn = vec(deg)) }
        }
        if (l.accel == 0.0 && l.ease == null) {
            if (abs(total) <= MAX_SPIN_CHUNK_DEG) return listOf(key(l.from, turn = vec(0.0)), key(l.from + l.forTicks, turn = vec(total)))
            val chunk = max(1, floor(MAX_SPIN_CHUNK_DEG / abs(l.degPerTick)).toInt())
            val ticks = (0 until l.forTicks step chunk).toMutableList().also { it += l.forTicks }
            return ticks.map { tau -> key(l.from + tau, turn = vec(l.degPerTick * tau)) }
        }
        if (l.accel == 0.0 && abs(total) <= MAX_SPIN_CHUNK_DEG) {
            return listOf(key(l.from, turn = vec(0.0)), key(l.from + l.forTicks, turn = vec(total), ease = l.ease))
        }
        val angles = (0..l.forTicks).map { tau ->
            val t = tau.toDouble()
            if (l.ease != null) total * e(l.ease, t / l.forTicks) else l.degPerTick * t + l.accel * t * t / 2.0
        }
        angles.zipWithNext().forEach { (a, b) ->
            require(abs(b - a) < 180.0) { "rate law: spin turns ${abs(b - a)} degrees in one tick; slerp cannot carry that, split the law" }
        }
        return angles.mapIndexed { tau, deg -> key(l.from + tau, turn = vec(deg)) }
    }

    /** Inverse of [Quaternion.fromRotationVector] on the shortest arc (angle in [0, 180]). */
    private fun toRotationVector(q: Quaternion): Vec3 {
        val n = q.normalized()
        val s = if (n.w < 0) -1.0 else 1.0
        val x = n.x * s
        val y = n.y * s
        val z = n.z * s
        val sin = sqrt(x * x + y * y + z * z)
        if (sin < 1e-12) return Vec3(0.0, 0.0, 0.0)
        val deg = Math.toDegrees(2.0 * atan2(sin, n.w * s))
        return Vec3(x / sin * deg, y / sin * deg, z / sin * deg)
    }
}
