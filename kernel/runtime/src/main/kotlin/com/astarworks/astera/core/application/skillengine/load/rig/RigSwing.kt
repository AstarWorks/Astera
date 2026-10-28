package com.astarworks.astera.core.application.skillengine.load.rig

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.Grammar
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.Span
import com.astarworks.astera.core.application.skillengine.load.grammar.TimeAt
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import com.astarworks.astera.core.domain.skillengine.op.RigSwingKey
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** Which way the blade's cutting edge faces while the tip travels: along the travel direction, or against it. */
public enum class SwingEdge { LEADING, TRAILING }

/**
 * One v3 swing segment (ADR-0303 rig section): the TIP travels from [from] to [to] (unit directions in the rig's parent frame),
 * optionally through [via], with [turns] extra full revolutions of the same great circle, over [ticks] ticks eased by [easeName].
 * [from] null = continue from the previous step's [to]. [at] null = start when the previous step ends (0 for the first).
 * [roll] is extra degrees of roll about the blade's long axis reached at the end (right-handed, eased like the swing).
 */
public data class SwingStep(
    val at: Int?,
    val ticks: Int,
    val from: Vec3?,
    val via: Vec3?,
    val to: Vec3?,
    val edge: SwingEdge?,
    val turns: Int,
    val easeName: String,
    val roll: Double,
    /** R30: a `{at, reset: true}` step is a duration-0 write of the rest pose at [at] (no tip path, so [to] / [edge] / [from] / [ticks] are absent). */
    val reset: Boolean = false,
)

/**
 * The blade in the rig frame (the same axes as `tip - root` and `edge` of `VisualOp.Rig`): [long] points root to tip, [edge] is
 * the cutting-edge direction, perpendicular to [long] in the blade plane (`content/frames/<item>.yaml` `axes.long` / `axes.edge`).
 */
public data class RigBlade(val long: Vec3, val edge: Vec3)

/**
 * The v3 rig swing (SKL-20 E6, founder 2026-10-29): a swing is written as WHERE THE TIP GOES and which way the EDGE faces, never as
 * cw / ccw. [load] lowers it at load time to the existing absolute rotation-vector wrist keys of `VisualOp.Rig.swing`, one key per
 * tick, so the runtime sampling (slerp between keys) needs no change and can never take the short way round: every step of the
 * lowered path is at most [MAX_STEP_DEG] degrees (a swing needing more per tick is a load error).
 *
 * YAML (one list entry per segment; directions are named vectors of the rig's parent / caster frame, right = -left):
 * ```
 * swing:
 *   - {at: 4, for: 6, from: {left: -1, up: 1, forward: 1}, to: {left: 1, up: -1, forward: 1}, edge: leading, turns: 0, ease: out_cubic}
 *   - {for: 5, to: {left: -1, up: 1, forward: 1}, edge: trailing}      # the way back: from = previous to
 * ```
 * Fields: `from` (first step only, else the previous `to`), `via` (optional waypoint), `to`, `edge: leading|trailing`, `turns`
 * (extra revolutions, default 0; with `via` they are added to the via-to-`to` arc), `for` (ticks), `at` (default: previous end),
 * `ease` (default linear), `roll` (default 0). Orientation: Q(t) = R(axis, angle(t)) * Q0 * R(long, roll(t)), `axis` = from x to
 * (so the tip turns right-handed about it: the short way from `from` to `to`; more than 180 degrees needs `via` or `turns`), and
 * Q0 puts the blade's long axis on `from` and its edge on `axis x from` (leading) or the opposite (trailing).
 */
public object RigSwings {
    /** No lowered step (tip, edge or roll) turns more than this in one tick, so slerp between keys is unambiguous. */
    public const val MAX_STEP_DEG: Double = 170.0

    private const val EPS = 1e-9
    private const val COLLINEAR = 1e-6

    private val from: Field<FrameVec?> = Field.optional("from", Types.nullable(FrameVecs.type), null, "tip direction at the start; absent = previous step's 'to'")
    private val via: Field<FrameVec?> = Field.optional("via", Types.nullable(FrameVecs.type), null, "optional tip direction passed on the way; the tip follows from-via and via-to great-circle arcs")
    private val to: Field<FrameVec?> = Field.optional("to", Types.nullable(FrameVecs.type), null, "tip direction at the end (required unless reset)")
    private val edge: Field<String?> = Field.optional("edge", Types.nullable(Types.enumOf("leading", "trailing")), null, "the cutting edge faces the tip's travel direction (leading) or against it (trailing); required unless reset")
    private val reset: Field<Value<Boolean>> = Field.optional("reset", Types.bool, Value.Literal(false), "true = a duration-0 write of the rest pose at 'at'; takes no other swing field")
    private val turns: Field<Value<Double>> = Field.optional("turns", Types.number, Value.Literal(0.0), "extra full revolutions of the swing circle (integer >= 0)")
    private val ease: Field<String> = Field.optional("ease", Types.id, "linear", "easing of the whole segment (any v1 easing name except step)")
    private val roll: Field<Value<Double>> = Field.optional("roll", Types.number, Value.Literal(0.0), "extra degrees of roll about the blade's long axis reached at the end (right-handed)")

    public val step: Fields = Fields.of("rig-swing-step", from, via, to, edge, turns, TimingSpec.at, TimingSpec.forSpan, ease, roll, reset)

    public val type: FieldType<List<SwingStep>> = object : FieldType<List<SwingStep>> {
        override val label = "list of {from, via, to, edge, turns, at, for, ease, roll}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<SwingStep> {
            val list = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            return list.mapIndexed { i, e -> decodeStep(e, ctx, "$path[$i]") }
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "items" to step.jsonSchema())
    }

    private fun decodeStep(raw: Any?, ctx: GrammarContext, path: String): SwingStep {
        val v = step.read(raw, ctx, path)
        val at = when (val a = v[TimingSpec.at]) {
            null -> null
            is TimeAt.Event -> ctx.fail("$path.at", "a swing step starts at a tick of the rig, not an event")
            is TimeAt.Tick -> tick(a.ticks, ctx, "$path.at", min = 0)
        }
        val resetFlag = (v[reset] as? Value.Literal)?.value ?: ctx.fail("$path.reset", "reset must be a literal true / false")
        if (resetFlag) {
            if (at == null) ctx.fail("$path.at", "a reset step needs 'at' (the rig tick of the rest-pose write)")
            for (f in listOf<Field<*>>(from, via, to, edge, TimingSpec.forSpan)) {
                if (v.isPresent(f)) ctx.fail("$path.${f.name}", "a reset step is a duration-0 write of the rest pose: it takes only 'at' and 'reset'")
            }
            if (v.isPresent(turns) || v.isPresent(ease) || v.isPresent(roll)) {
                ctx.fail(path, "a reset step is a duration-0 write of the rest pose: it takes only 'at' and 'reset'")
            }
            return SwingStep(at, 0, null, null, null, null, 0, "linear", 0.0, reset = true)
        }
        val span = v[TimingSpec.forSpan] ?: ctx.fail(path, "swing step needs 'for' (ticks)")
        val ticks = when (span) {
            is Span.Ticks -> tick(span.ticks, ctx, "$path.for", min = 1)
            Span.UntilCastEnds -> ctx.fail("$path.for", "a swing step needs a number of ticks, not 'cast'")
        }
        val turnsN = number(v[turns], ctx, "$path.turns")
        if (turnsN < 0.0 || turnsN != Math.rint(turnsN)) ctx.fail("$path.turns", "turns must be an integer >= 0 (extra full revolutions), got $turnsN")
        val easeName = v[ease]
        if (Easing.fromKey(easeName) == null || easeName.trim().lowercase() == "step") {
            ctx.fail("$path.ease", "'$easeName' is not a smooth easing name (linear, in_/out_/in_out_ + sine quad cubic quart quint expo circ back elastic bounce)")
        }
        return SwingStep(
            at = at,
            ticks = ticks,
            from = v[from]?.let { dir(it, ctx, "$path.from") },
            via = v[via]?.let { dir(it, ctx, "$path.via") },
            to = dir(v[to] ?: ctx.fail("$path.to", "swing step needs 'to' (the tip direction at the end)"), ctx, "$path.to"),
            edge = when (v[edge]) {
                "leading" -> SwingEdge.LEADING
                "trailing" -> SwingEdge.TRAILING
                else -> ctx.fail("$path.edge", "swing step needs 'edge: leading|trailing'")
            },
            turns = turnsN.toInt(),
            easeName = easeName,
            roll = number(v[roll], ctx, "$path.roll"),
        )
    }

    private fun number(v: Value<Double>, ctx: GrammarContext, path: String): Double =
        ctx.resolveNumber(v) ?: ctx.fail(path, "a swing is lowered at load: this needs a literal or a numeric \${param}, not an expression")

    private fun tick(v: Value<Double>, ctx: GrammarContext, path: String, min: Int): Int {
        val d = number(v, ctx, path)
        if (d != Math.rint(d) || d < min) ctx.fail(path, "must be an integer >= $min (ticks), got $d")
        return d.toInt()
    }

    /** A unit tip direction in the parent's local axes (x = left, y = up, z = forward). */
    private fun dir(fv: FrameVec, ctx: GrammarContext, path: String): Vec3 {
        val raw = when (fv) {
            is FrameVec.Parts -> Vec3(number(fv.left, ctx, "$path.left"), number(fv.up, ctx, "$path.up"), number(fv.forward, ctx, "$path.forward"))
            is FrameVec.Whole -> ctx.fail(path, "a swing direction is lowered at load: write {forward, up, left} numbers")
        }
        if (raw.lengthSquared() < EPS) ctx.fail(path, "a swing direction must not be the zero vector")
        return raw.normalize()
    }

    /** Decode [raw] (the `swing:` list) and lower it. Load errors come back as values. */
    public fun load(
        raw: Any?,
        blade: RigBlade,
        tilt: Vec3,
        rest: Vec3,
        ctx: GrammarContext,
        path: String,
    ): Result<List<RigSwingKey>, SkillDecodeError> = Grammar.guard { lower(type.decode(raw, ctx, path), blade, tilt, rest, ctx, path) }

    /**
     * Lowers [steps] to absolute rotation-vector wrist keys for a rig with the given [tilt] / [rest] rotation vectors (the rig
     * frame is `tilt * wrist * rest`, so `wrist = tilt^-1 * Q * rest^-1`). Aborts through [ctx] on a bad swing.
     */
    public fun lower(steps: List<SwingStep>, blade: RigBlade, tilt: Vec3, rest: Vec3, ctx: GrammarContext, path: String): List<RigSwingKey> {
        val long = blade.long.normalize()
        val edgeRaw = blade.edge - long * blade.edge.dot(long)
        if (long.lengthSquared() < EPS || edgeRaw.lengthSquared() < EPS) ctx.fail(path, "blade long / edge axes must be non-zero and not parallel")
        val edgeAxis = edgeRaw.normalize()
        val tiltInv = Quaternion.fromRotationVector(tilt).conjugate()
        val restInv = Quaternion.fromRotationVector(rest).conjugate()

        val keys = ArrayList<RigSwingKey>()
        var prevEndTick = -1
        var prevEndQ: Quaternion? = null
        var prevTo: Vec3? = null
        steps.forEachIndexed { i, s ->
            val p = "$path[$i]"
            val start = s.at ?: (if (prevEndTick >= 0) prevEndTick else 0)
            if (start < prevEndTick) ctx.fail("$p.at", "step starts at $start but the previous step runs until $prevEndTick")
            if (s.reset) {
                // R30: the rest pose is wrist = identity (rig frame = tilt * wrist * rest); a cut, never a sweep, so MAX_STEP_DEG does not apply.
                keys += RigSwingKey(start, Vec3(0.0, 0.0, 0.0), if (keys.isEmpty()) "linear" else "step")
                prevEndTick = start
                prevEndQ = null
                prevTo = null
                return@forEachIndexed
            }
            val fromDir = s.from ?: prevTo ?: ctx.fail("$p.from", "the first swing step needs 'from'")
            val arcs = arcs(fromDir, s, long, edgeAxis, ctx, p)
            val total = arcs.last().offset + arcs.last().angle
            val easing = Easing.fromKey(s.easeName)!!
            val contiguous = prevEndTick == start

            var before: Quaternion? = if (contiguous) prevEndQ else null
            var beforeSigma = 0.0
            var beforeRoll = 0.0
            for (k in 0..s.ticks) {
                val eased = easing.apply(k.toDouble() / s.ticks)
                val sigma = eased * total
                val rollDeg = eased * s.roll
                val q = poseAt(arcs, sigma, rollDeg, long)
                val prev = before
                if (k == 0) {
                    if (prev != null) {
                        val gap = Math.toDegrees(prev.angleTo(q))
                        if (gap > MAX_STEP_DEG) {
                            ctx.fail("$p.edge", "this step starts with the blade ${"%.0f".format(gap)} degrees away from where the previous step ended: reverse a there-and-back with 'edge: trailing', or add a 'roll'")
                        }
                    } else {
                        keys += RigSwingKey(start, rotationVector(tiltInv * q * restInv), if (keys.isEmpty()) "linear" else "step")
                    }
                } else {
                    val tooFar = abs(sigma - beforeSigma) > MAX_STEP_DEG || abs(rollDeg - beforeRoll) > MAX_STEP_DEG ||
                        (prev != null && Math.toDegrees(prev.angleTo(q)) > MAX_STEP_DEG)
                    if (tooFar) {
                        ctx.fail("$p.for", "the swing turns ${"%.0f".format(abs(sigma - beforeSigma))} degrees in one tick (tick $k of ${s.ticks}), more than ${MAX_STEP_DEG.toInt()}: lengthen 'for' or lower 'turns', slerp could not tell its direction")
                    }
                    keys += RigSwingKey(start + k, rotationVector(tiltInv * q * restInv), "linear")
                }
                before = q
                beforeSigma = sigma
                beforeRoll = rollDeg
            }
            prevEndTick = start + s.ticks
            prevEndQ = before
            prevTo = s.to
        }
        return keys
    }

    /** One great-circle arc of the tip path: unit axis [n], start direction [d0], swept [angle] degrees starting at [offset]. */
    private class Arc(val n: Vec3, val offset: Double, val angle: Double, val q0: Quaternion)

    private fun arcs(from: Vec3, s: SwingStep, long: Vec3, edge: Vec3, ctx: GrammarContext, path: String): List<Arc> {
        val waypoints = listOfNotNull(from, s.via, s.to)
        val out = ArrayList<Arc>()
        var offset = 0.0
        for (j in 0 until waypoints.size - 1) {
            val a = waypoints[j]
            val b = waypoints[j + 1]
            val cross = a.cross(b)
            val sinAngle = cross.length()
            if (sinAngle < COLLINEAR) {
                val name = if (waypoints.size == 3) (if (j == 0) "from-via" else "via-to") else "from-to"
                val what = if (a.dot(b) > 0) "the same direction" else "opposite directions"
                ctx.fail(path, "the swing arc $name joins $what, so its turning axis is undefined: add a 'via' that is not on that line")
            }
            val n = cross * (1.0 / sinAngle)
            var angle = Math.toDegrees(atan2(sinAngle, a.dot(b)))
            if (j == waypoints.size - 2) angle += 360.0 * s.turns
            val tangent = n.cross(a) * (if (s.edge == SwingEdge.LEADING) 1.0 else -1.0)
            out += Arc(n, offset, angle, basis(long, edge, a, tangent))
            offset += angle
        }
        return out
    }

    /** Orientation that puts the blade's [long] on [d] and its [edge] on [t] ([t] perpendicular to [d]). */
    private fun basis(long: Vec3, edge: Vec3, d: Vec3, t: Vec3): Quaternion {
        val q1 = between(long, d)
        val e1 = q1.rotate(edge)
        val signed = atan2(d.dot(e1.cross(t)), e1.dot(t))
        return Quaternion.axisAngle(d, signed) * q1
    }

    private fun between(a: Vec3, b: Vec3): Quaternion {
        val dot = a.dot(b)
        if (dot > 1.0 - 1e-12) return Quaternion.IDENTITY
        if (dot < -1.0 + 1e-12) {
            val helper = if (abs(a.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
            return Quaternion.axisAngle(a.cross(helper), Math.PI)
        }
        return Quaternion.axisAngle(a.cross(b), acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun poseAt(arcs: List<Arc>, sigma: Double, rollDeg: Double, long: Vec3): Quaternion {
        val arc = arcs.lastOrNull { it.offset <= sigma } ?: arcs.first()
        val phi = Math.toRadians(sigma - arc.offset)
        return Quaternion.axisAngle(arc.n, phi) * arc.q0 * Quaternion.axisAngle(long, Math.toRadians(rollDeg))
    }

    /** Rotation vector (axis * degrees, angle in [0, 180]) of a unit quaternion. */
    private fun rotationVector(q: Quaternion): Vec3 {
        val n = q.normalized()
        val s = if (n.w < 0.0) Quaternion(-n.x, -n.y, -n.z, -n.w) else n
        val sinHalf = sqrt(s.x * s.x + s.y * s.y + s.z * s.z)
        if (sinHalf < 1e-12) return Vec3(0.0, 0.0, 0.0)
        val k = Math.toDegrees(2.0 * atan2(sinHalf, s.w)) / sinHalf
        return Vec3(s.x * k, s.y * k, s.z * k)
    }
}
