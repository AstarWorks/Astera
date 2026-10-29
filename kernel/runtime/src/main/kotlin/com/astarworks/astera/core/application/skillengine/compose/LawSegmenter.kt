package com.astarworks.astera.core.application.skillengine.compose

import com.astarworks.astera.core.application.skillengine.load.motion.AlignMotion
import com.astarworks.astera.core.application.skillengine.load.motion.FollowMotion
import com.astarworks.astera.core.application.skillengine.load.motion.KeysMotion
import com.astarworks.astera.core.application.skillengine.load.motion.LawKey
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaw
import com.astarworks.astera.core.application.skillengine.load.motion.OrbitMotion
import com.astarworks.astera.core.application.skillengine.load.motion.RateMotion
import com.astarworks.astera.core.application.skillengine.load.motion.StretchMotion
import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.spec.Lowered
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.EngineConfig
import com.astarworks.astera.core.domain.skillengine.motion.FadeRate
import com.astarworks.astera.core.domain.skillengine.motion.GrowRate
import com.astarworks.astera.core.domain.skillengine.motion.MoveRate
import com.astarworks.astera.core.domain.skillengine.motion.SpinRate
import com.astarworks.astera.core.domain.skillengine.table.Action
import com.astarworks.astera.core.domain.skillengine.table.InstanceId
import com.astarworks.astera.core.domain.skillengine.table.Row
import com.astarworks.astera.core.domain.skillengine.table.SrcPath
import com.astarworks.astera.core.domain.skillengine.table.Val
import com.astarworks.astera.core.domain.skillengine.table.WriteStats
import com.astarworks.astera.core.domain.skillengine.math.Quaterniond
import com.astarworks.astera.core.domain.skillengine.math.Vector3d
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The state of one object under its laws at one moment (ADR-0303 3.2): offset (translate laws summed), rotation (composed outer to inner),
 * scale (multiplied), alpha (multiplied). A reactive law (`follow`, `stretch`, `align` toward an anchor) names a live cell instead of a
 * number; [liveOpen] says the reactive window is still running at this moment.
 */
public data class LawState(
    val offset: Vec3,
    val rot: Quaternion,
    val scale: Vec3,
    val alpha: Double,
    val liveOffset: String? = null,
    val liveRot: String? = null,
    val liveScale: String? = null,
    val liveOpen: Boolean = false,
)

/** One send of the transformation (ADR-0303 section 4): at [tick] the client is told to be at [state] after [durationTicks]. */
public data class LawSegment(val tick: Int, val durationTicks: Int, val state: LawState)

/**
 * Lowers decoded motion laws to the minimum set of `Key` rows (ADR-0303 sections 3 and 4, SKL-22 W1-08).
 *
 * Every law is a closed function of tau; [eval] composes them. The timeline is cut at law / key boundaries, then each non-constant interval
 * is cut again by the error budget (ADR 3.7 formula for the first guess, then verified): a piece is accepted when the client's own
 * interpolation (lerp for vectors, slerp for the rotation) stays within `segmentErrorBlocks` / `segmentErrorDeg` of the closed form on every
 * tick, lasts at most `maxSegmentTicks`, and turns at most `splitTurnDeg`. When the guess would write at least every second tick it writes
 * every tick instead. A segment is `Key(tick = s, duration = d, state at s + d)`.
 *
 * Laws SUM and HOLD (item 15a): a rate law's contribution persists after its window and is neutral before it begins; laws on one channel add
 * (offsets), compose in list order (rotations) or multiply (scale, alpha). A jump (a value that differs just before and just after a tick) is a
 * duration-0 write, and the next segment starts the tick after: one write per object per tick (check 12). `fade` has no field on `Key`: it is one
 * `Set <field>=<value>` row per tick (`text_opacity` on a text or sprite display, `count` on a particle, `volume` on a sound).
 */
public object LawSegmenter {
    private const val EPS = 1e-9
    private const val DEFAULT_FADE_FIELD = "text_opacity"

    /** A keys `color` channel is one `Set color=#rrggbb` row per changed tick (text and sprite displays), like the fade. */
    private const val COLOR_FIELD = "color"

    public fun segments(instance: InstanceId, laws: List<MotionLaw>, lc: LowerContext, fadeField: String = DEFAULT_FADE_FIELD): Result<Lowered, SkillDecodeError> =
        // rows are relative to the occurrence: GroupUnroller shifts them by the occurrence tick, so adding lc.clockOffset here would count it twice
        lower(instance, laws, lc.config, lc.src, 0, fadeField)

    /** [segments] without a [LowerContext]: [base] is the instance's start tick. */
    public fun lower(
        instance: InstanceId, laws: List<MotionLaw>, config: EngineConfig, src: SrcPath, base: Int = 0, fadeField: String = DEFAULT_FADE_FIELD,
    ): Result<Lowered, SkillDecodeError> {
        val m = Model(laws)
        val rows = ArrayList<Row>()
        for (s in m.plan(config)) {
            val st = s.state
            rows += Row(
                base + s.tick, instance,
                Action.Key(
                    instance, s.durationTicks,
                    st.liveOffset?.let { Val.Live(it, null) } ?: Val.Known(Vector3d(st.offset.x, st.offset.y, st.offset.z)),
                    st.liveRot?.let { Val.Live(it, null) } ?: Val.Known(Quaterniond(st.rot.x, st.rot.y, st.rot.z, st.rot.w)),
                    st.liveScale?.let { Val.Live(it, null) } ?: Val.Known(Vector3d(st.scale.x, st.scale.y, st.scale.z)),
                ),
                src,
            )
        }
        for ((tick, alpha) in m.fadeSteps()) rows += Row(base + tick, instance, Action.Set(fadeField, Val.Known(alpha)), src)
        for ((tick, rgb) in m.colorSteps()) rows += Row(base + tick, instance, Action.Set(COLOR_FIELD, Val.Known(rgb)), src)
        rows.sortBy { it.tick }
        checkOneWritePerTick(rows, src)?.let { return Result.Failure(it) }
        val perTick = rows.groupingBy { it.tick }.eachCount()
        return Result.Success(Lowered(rows = rows, instances = listOf(instance), writes = mapOf(instance to WriteStats(rows.size, perTick.values.maxOrNull() ?: 0))))
    }

    /** Check 12: one `Key` / `Teleport` and one `Set` per field, per object, per tick. Null when the rows are clean. */
    public fun checkOneWritePerTick(rows: List<Row>, src: SrcPath): SkillDecodeError? {
        val seen = HashSet<Triple<Int, InstanceId, String>>()
        for (r in rows) {
            val channel = when (val a = r.action) {
                is Action.Key, is Action.Teleport -> "transform"
                is Action.Set -> "set:${a.field}"
                else -> continue
            }
            if (!seen.add(Triple(r.tick, r.instance, channel))) {
                return SkillDecodeError.MachineCheck(12, "more than one write to ${r.instance} ($channel) on tick ${r.tick}", src.toString())
            }
        }
        return null
    }

    /** The closed form at [tick] (fractional ticks allowed): what a particle occurrence or a sound at that tick samples. */
    public fun valueAt(laws: List<MotionLaw>, tick: Double): LawState = Model(laws).eval(tick, left = false)

    /** The transform segments of [laws], tick relative to the instance start: what a gameplay frame exports to its riders (ADR 1.1). */
    public fun segmentsOf(laws: List<MotionLaw>, config: EngineConfig): List<LawSegment> = Model(laws).plan(config)

    /**
     * A gameplay frame moved only by closed-form laws EXPORTS its segments (ADR 1.1): `Teleport` rows carrying the position at the end of the
     * segment, with the duration; riders interpolate the same segment. Reactive laws are not exportable (they are gameplay anchors, re-sent every tick).
     */
    public fun exportFrame(instance: InstanceId, laws: List<MotionLaw>, config: EngineConfig, src: SrcPath, base: Int = 0): Lowered {
        val rows = segmentsOf(laws, config).map { s ->
            val fwd = s.state.rot.rotate(Vec3(0.0, 0.0, 1.0))
            val yaw = Math.toDegrees(atan2(-fwd.x, fwd.z))
            val pitch = Math.toDegrees(-asin(fwd.y.coerceIn(-1.0, 1.0)))
            Row(
                base + s.tick, instance,
                Action.Teleport(instance, s.durationTicks, Val.Known(Vector3d(s.state.offset.x, s.state.offset.y, s.state.offset.z)), Val.Known(yaw), Val.Known(pitch)),
                src,
            )
        }
        val perTick = rows.groupingBy { it.tick }.eachCount()
        return Lowered(rows = rows, instances = listOf(instance), writes = mapOf(instance to WriteStats(rows.size, perTick.values.maxOrNull() ?: 0)))
    }

    // ------------------------------------------------------------------------------------------ model

    private fun ease(name: String?, u: Double): Double {
        if (name == null) return u
        if (name.trim().lowercase() == "step") return if (u >= 1.0) 1.0 else 0.0
        return Easing.fromKey(name)?.apply(u) ?: u
    }

    private fun fromTo(a: Vec3, b: Vec3): Quaternion {
        val u = a.normalize()
        val v = b.normalize()
        if (u.lengthSquared() == 0.0 || v.lengthSquared() == 0.0) return Quaternion.IDENTITY
        val d = u.dot(v)
        if (d > 1.0 - 1e-12) return Quaternion.IDENTITY
        if (d < -1.0 + 1e-12) {
            val perp = if (abs(u.x) < 0.9) Vec3(1.0, 0.0, 0.0).cross(u) else Vec3(0.0, 1.0, 0.0).cross(u)
            return Quaternion.axisAngle(perp, Math.PI)
        }
        val c = u.cross(v)
        return Quaternion(c.x, c.y, c.z, 1.0 + d).normalized()
    }

    private class Model(laws: List<MotionLaw>) {
        private val ordered = laws.sortedWith(compareBy({ it.order }, { startOf(it) }))
        private val moves = ordered.filterIsInstance<RateMotion>().map { it.rate }.filterIsInstance<MoveRate>()

        /** A law that begins at [from] is neutral before it; [left] reads the tick as "just before" so a law beginning exactly there has not begun. */
        private fun begun(t: Double, from: Int, left: Boolean): Boolean = if (left) t > from else t >= from

        fun eval(t: Double, left: Boolean): LawState {
            var offset = Vec3.ZERO
            var rot = Quaternion.IDENTITY
            var scale = Vec3(1.0, 1.0, 1.0)
            var alpha = 1.0
            var liveOffset: String? = null
            var liveRot: String? = null
            var liveScale: String? = null
            var liveOpen = false
            for (law in ordered) {
                when (law) {
                    is RateMotion -> if (begun(t, law.rate.from, left)) when (val r = law.rate) {
                        is MoveRate -> offset += moveAt(r, t)
                        is SpinRate -> rot *= spinAt(r, t)
                        is GrowRate -> scale = mul(scale, growAt(r, t))
                        is FadeRate -> alpha *= fadeAt(r, t)
                    }
                    is KeysMotion -> {
                        keyChannel(law.keys, t, left, { it.offset }, { a, b, f -> a + (b - a) * f })?.let { offset += it }
                        keyChannel(law.keys, t, left, { it.pose?.let(Quaternion::fromRotationVector) }, { a, b, f -> Quaternion.slerp(a, b, f) })?.let { rot *= it }
                        keyChannel(law.keys, t, left, { it.scale }, { a, b, f -> a + (b - a) * f })?.let { scale = mul(scale, it) }
                        keyChannel(law.keys, t, left, { it.alpha }, { a, b, f -> a + (b - a) * f })?.let { alpha *= it }
                    }
                    is OrbitMotion -> if (begun(t, law.at, left)) {
                        val u = unit(t, law.at, law.forTicks)
                        val angle = law.phaseDeg + law.degPerTick * law.forTicks * ease(law.ease, u)
                        val q = Quaternion.axisAngle(law.axis, Math.toRadians(angle))
                        offset += law.pivot + q.rotate(law.pivot * -1.0)
                        when (law.face) {
                            "center" -> rot *= q
                            "tangent" -> rot *= q * Quaternion.axisAngle(law.axis, Math.PI / 2.0)
                        }
                    }
                    is AlignMotion -> if (begun(t, law.at, left)) {
                        if (law.toVelocity) {
                            val tc = t.coerceIn(law.at.toDouble(), (law.at + law.forTicks).toDouble())
                            val v = velocityAt(tc)
                            val target = fromTo(law.axis, v)
                            rot *= if (law.ease == null) target else Quaternion.slerp(Quaternion.IDENTITY, target, ease(law.ease, unit(t, law.at, law.forTicks)))
                        } else {
                            liveRot = "align:${law.axisName}>${law.target}^${law.up}"
                            liveOpen = liveOpen || t < law.at + law.forTicks
                        }
                    }
                    is FollowMotion -> if (begun(t, law.at, left)) {
                        liveOffset = "follow:${law.anchor}"
                        liveOpen = liveOpen || t < law.at + law.forTicks
                    }
                    is StretchMotion -> if (begun(t, law.at, left)) {
                        val n = "stretch:${law.base}>${law.tip}"
                        liveOffset = n
                        liveRot = n
                        liveScale = n
                        liveOpen = liveOpen || t < law.at + law.forTicks
                    }
                }
            }
            return LawState(offset, rot, scale, alpha, liveOffset, liveRot, liveScale, liveOpen)
        }

        private fun unit(t: Double, at: Int, forTicks: Int): Double = ((t - at) / forTicks).coerceIn(0.0, 1.0)
        private fun tau(t: Double, from: Int, forTicks: Int): Double = (t - from).coerceIn(0.0, forTicks.toDouble())
        private fun mul(a: Vec3, b: Vec3) = Vec3(a.x * b.x, a.y * b.y, a.z * b.z)

        private fun moveAt(l: MoveRate, t: Double): Vec3 {
            val tau = tau(t, l.from, l.forTicks)
            return l.start + l.velocity * (l.forTicks * ease(l.ease, tau / l.forTicks))
        }

        private fun spinAt(l: SpinRate, t: Double): Quaternion {
            val tau = tau(t, l.from, l.forTicks)
            val total = l.degPerTick * l.forTicks
            val deg = if (l.ease != null) total * ease(l.ease, tau / l.forTicks) else l.degPerTick * tau + l.accel * tau * tau / 2.0
            return Quaternion.axisAngle(l.axis, Math.toRadians(deg)) * Quaternion.fromRotationVector(l.start)
        }

        private fun growAt(l: GrowRate, t: Double): Vec3 {
            val tau = tau(t, l.from, l.forTicks)
            if (l.accel.lengthSquared() == 0.0) return l.start + l.perTick * (l.forTicks * ease(l.ease, tau / l.forTicks))
            return l.start + l.perTick * tau + l.accel * (tau * tau / 2.0)
        }

        private fun fadeAt(l: FadeRate, t: Double): Double {
            val k = Math.pow(1.0 - ease(l.ease, unit(t, l.from, l.forTicks)), l.exponent)
            return l.to + (l.start - l.to) * k
        }

        /** Direction of the attached move laws: the central difference over one tick of their closed form. */
        private fun velocityAt(t: Double): Vec3 {
            fun sum(x: Double) = moves.fold(Vec3.ZERO) { acc, m -> acc + moveAt(m, x) }
            return sum(t + 0.5) - sum(t - 0.5)
        }

        /** Neutral (null) before the first key; held at the last key after it; a step key reads "just before" on the left. */
        private fun <T> keyChannel(keys: List<LawKey>, t: Double, left: Boolean, pick: (LawKey) -> T?, lerp: (T, T, Double) -> T): T? {
            val ks = keys.filter { pick(it) != null }
            if (ks.isEmpty()) return null
            if (t < ks.first().tick || (left && t == ks.first().tick.toDouble())) return null
            if (t == ks.first().tick.toDouble()) return pick(ks.first())
            if (t >= ks.last().tick && !(left && ks.last().ease.isStep() && t == ks.last().tick.toDouble())) return pick(ks.last())
            val hi = ks.indexOfFirst { it.tick >= t }
            val a = ks[hi - 1]
            val b = ks[hi]
            val raw = (t - a.tick) / max(1, b.tick - a.tick)
            val f = if (b.ease.isStep()) (if (t >= b.tick && !left) 1.0 else 0.0) else ease(b.ease, raw)
            return lerp(pick(a)!!, pick(b)!!, f)
        }

        private fun String?.isStep() = this != null && trim().lowercase() == "step"

        // -------------------------------------------------------------------------------------- fade

        /** The ticks (relative to the instance start) where the opacity changes, with the new value: one `Set` row each. */
        fun fadeSteps(): List<Pair<Int, Double>> {
            val ticks = java.util.TreeSet<Int>()
            for (law in ordered) when (law) {
                is RateMotion -> (law.rate as? FadeRate)?.let { ticks += it.from; ticks += it.from + it.forTicks }
                is KeysMotion -> law.keys.filter { it.alpha != null }.forEach { ticks += it.tick }
                else -> Unit
            }
            if (ticks.isEmpty()) return emptyList()
            val out = ArrayList<Pair<Int, Double>>()
            var prev = 1.0
            for (t in ticks.first()..ticks.last()) {
                val v = eval(t.toDouble(), left = false).alpha
                if (abs(v - prev) > EPS) { out += t to v; prev = v }
            }
            return out
        }

        /** The ticks (relative to the instance start) where a keys `color` channel changes, with the new `#rrggbb`: one `Set` row each. */
        fun colorSteps(): List<Pair<Int, String>> {
            val keyed = ordered.filterIsInstance<KeysMotion>().filter { l -> l.keys.any { it.color != null } }
            if (keyed.isEmpty()) return emptyList()
            val ticks = java.util.TreeSet<Int>()
            keyed.forEach { l -> l.keys.filter { it.color != null }.forEach { ticks += it.tick } }
            val out = ArrayList<Pair<Int, String>>()
            var prev: String? = null
            for (t in ticks.first()..ticks.last()) {
                var rgb: Vec3? = null
                for (l in keyed) keyChannel(l.keys, t.toDouble(), false, { it.color }, { a, b, f -> a + (b - a) * f })?.let { rgb = it }
                val hex = rgb?.let { c -> "#%02x%02x%02x".format(c.x.roundToInt().coerceIn(0, 255), c.y.roundToInt().coerceIn(0, 255), c.z.roundToInt().coerceIn(0, 255)) }
                if (hex != null && hex != prev) { out += t to hex; prev = hex }
            }
            return out
        }

        // -------------------------------------------------------------------------------------- planning

        private fun cuts(): java.util.TreeSet<Int> {
            val cuts = java.util.TreeSet<Int>()
            for (law in ordered) when (law) {
                is RateMotion -> {
                    val r = law.rate
                    if (r !is FadeRate) {
                        cuts += r.from
                        cuts += r.from + r.forTicks
                        if (r is SpinRate && r.accel == 0.0 && r.ease == null && abs(r.degPerTick * r.forTicks) > 170.0) {
                            val chunk = max(1, floor(170.0 / abs(r.degPerTick)).toInt())
                            var k = chunk
                            while (k < r.forTicks) { cuts += r.from + k; k += chunk }
                        }
                    }
                }
                is KeysMotion -> law.keys.forEach { k -> if (k.offset != null || k.pose != null || k.scale != null) cuts += k.tick }
                is OrbitMotion -> { cuts += law.at; cuts += law.at + law.forTicks }
                is AlignMotion -> reactiveCuts(cuts, law.at, law.forTicks, if (law.toVelocity) 0 else law.step)
                is FollowMotion -> reactiveCuts(cuts, law.at, law.forTicks, law.step)
                is StretchMotion -> reactiveCuts(cuts, law.at, law.forTicks, law.step)
            }
            return cuts
        }

        private fun reactiveCuts(cuts: MutableSet<Int>, at: Int, forTicks: Int, step: Int) {
            cuts += at
            cuts += at + forTicks
            if (step > 0) { var k = at + step; while (k < at + forTicks) { cuts += k; k += step } }
        }

        /** A jump: the value just before [c] differs from the value at [c]. It is a duration-0 write and the next segment starts at c + 1. */
        private fun jumpAt(c: Int): Boolean = !sameValue(eval(c.toDouble(), left = true), eval(c.toDouble(), left = false))

        fun plan(config: EngineConfig): List<LawSegment> {
            val cutList = cuts().toList()
            if (cutList.isEmpty()) return emptyList()
            val out = ArrayList<LawSegment>()
            var minStart = Int.MIN_VALUE
            var client = IDENTITY
            for (i in cutList.indices) {
                val a = cutList[i]
                val right = eval(a.toDouble(), left = false)
                if (!right.liveOpen && jumpAt(a)) {
                    out += LawSegment(a, 0, right)
                    client = right
                    minStart = a + 1
                }
                if (i == cutList.size - 1) break
                val b = cutList[i + 1]
                val leftEnd = jumpAt(b)
                val sb = eval(b.toDouble(), left = leftEnd)
                val mid = eval((a + b) / 2.0, left = false)
                if (!right.liveOpen && sameValue(right, sb) && sameValue(right, mid)) continue
                val start = max(a, minStart)
                if (start >= b) {
                    // no room for a segment before the next cut: write its state there (a jump at b writes it itself)
                    val st = eval(b.toDouble(), left = false)
                    if (!leftEnd && !sameValue(client, st)) {
                        out += LawSegment(b, 0, st)
                        client = st
                        minStart = b + 1
                    }
                    continue
                }
                val override = if (start > a) client else null
                for ((s, e) in subdivide(start, b, config, leftEnd, override)) {
                    val st = eval(e.toDouble(), left = e == b && leftEnd)
                    out += LawSegment(s, e - s, st)
                    client = st
                }
            }
            return out
        }

        private fun sameValue(x: LawState, y: LawState): Boolean =
            dist(x.offset, y.offset) < EPS && x.rot.angleTo(y.rot) < 1e-6 /* acos loses ~1e-8 near 1 */ && dist(x.scale, y.scale) < EPS &&
                x.liveOffset == y.liveOffset && x.liveRot == y.liveRot && x.liveScale == y.liveScale

        private fun dist(a: Vec3, b: Vec3) = a.distance(b)

        /**
         * Error (in budget units, 1.0 = at the limit) of the client interpolating [s]..[e] against the closed form. [start] overrides the state
         * the client holds at [s] (after a jump the client has not moved yet).
         */
        private fun pieceCost(s: Int, e: Int, config: EngineConfig, leftEnd: Boolean, start: LawState?): Double {
            val ls = start ?: eval(s.toDouble(), left = false)
            val le = eval(e.toDouble(), left = leftEnd)
            var worst = 0.0
            val turn = Math.toDegrees(ls.rot.angleTo(le.rot))
            if (turn > config.splitTurnDeg + EPS) return Double.MAX_VALUE
            for (t in (if (start != null) s else s + 1) until e) {
                val u = (t - s).toDouble() / (e - s)
                val x = eval(t.toDouble(), left = false)
                worst = max(
                    worst,
                    max(
                        max(
                            dist(x.offset, ls.offset + (le.offset - ls.offset) * u) / config.segmentErrorBlocks,
                            dist(x.scale, ls.scale + (le.scale - ls.scale) * u) / config.segmentErrorBlocks,
                        ),
                        Math.toDegrees(x.rot.angleTo(Quaternion.slerp(ls.rot, le.rot, u))) / config.segmentErrorDeg,
                    ),
                )
            }
            return worst
        }

        /** Second difference of the closed form in budget units: the f'' of the 3.7 formula. */
        private fun curvature(a: Int, b: Int, config: EngineConfig): Double {
            var worst = 0.0
            for (t in a + 1 until b) {
                val p = eval((t - 1).toDouble(), false)
                val c = eval(t.toDouble(), false)
                val n = eval((t + 1).toDouble(), false)
                worst = max(
                    worst,
                    max(
                        max(
                            dist(p.offset + n.offset, c.offset * 2.0) / config.segmentErrorBlocks,
                            dist(p.scale + n.scale, c.scale * 2.0) / config.segmentErrorBlocks,
                        ),
                        2.0 * Math.toDegrees(c.rot.angleTo(Quaternion.slerp(p.rot, n.rot, 0.5))) / config.segmentErrorDeg,
                    ),
                )
            }
            return worst
        }

        private fun subdivide(a: Int, b: Int, config: EngineConfig, leftEnd: Boolean, override: LawState?): List<Pair<Int, Int>> {
            val len = b - a
            fun pieces(m: Int): List<Pair<Int, Int>> {
                val bounds = (0..m).map { a + (len.toDouble() * it / m).roundToInt() }.distinct()
                return bounds.zipWithNext()
            }
            if (len <= 1) return listOf(a to b)
            val live = eval(a.toDouble(), false).liveOpen
            val minM = max(1, ceil(len.toDouble() / config.maxSegmentTicks).toInt())
            val fpp = if (live) 0.0 else curvature(a, b, config)
            // 3.7: h = sqrt(8 * budget / max|f''|), m = ceil(for / h); budget is 1.0 in the normalised units
            val guess = if (fpp < 1e-12) 1 else ceil(len / sqrt(8.0 / fpp)).toInt()
            var m = max(minM, guess)
            // a live cell is re-sent on its own step cuts, so the piece count is not collapsed to one per tick
            if (!live && m * 2 >= len) m = len
            while (m < len) {
                val ps = pieces(m)
                val ok = ps.all { (s, e) ->
                    e - s <= config.maxSegmentTicks &&
                        (live || pieceCost(s, e, config, e == b && leftEnd, override.takeIf { s == a }) <= 1.0)
                }
                if (ok) return ps
                m++
                if (!live && m * 2 >= len) m = len
            }
            return pieces(len)
        }
    }

    private val IDENTITY = LawState(Vec3.ZERO, Quaternion.IDENTITY, Vec3(1.0, 1.0, 1.0), 1.0)

    private fun startOf(law: MotionLaw): Int = when (law) {
        is RateMotion -> law.rate.from
        is KeysMotion -> law.keys.first().tick
        is OrbitMotion -> law.at
        is AlignMotion -> law.at
        is FollowMotion -> law.at
        is StretchMotion -> law.at
    }
}
