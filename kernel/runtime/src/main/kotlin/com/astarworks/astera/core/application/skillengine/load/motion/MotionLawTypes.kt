package com.astarworks.astera.core.application.skillengine.load.motion

import com.astarworks.astera.core.application.skillengine.expr.cel.EaseCurves
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.Field
import com.astarworks.astera.core.application.skillengine.load.grammar.Fields
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarSource
import com.astarworks.astera.core.application.skillengine.load.grammar.Rotation
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A motion vector in the frame grammar: `{v, w, s}` coefficients of the object's own frame (v = forward, w = up, s = v x w = right), each
 * a literal, `${param}` or `= expr`; a missing axis is 0. There is no `{forward, up, left}` spelling and no `= expr` for the whole vector.
 */
internal data class MotionVec(val v: Value<Double>, val w: Value<Double>, val s: Value<Double>) {
    /** The vector as a domain [Vec3] in the local convention (x = left = -s, y = up, z = forward) for a position / offset / velocity. */
    fun toLocal(): Value<Vec3> = if (v is Value.Literal && w is Value.Literal && s is Value.Literal) {
        Value.Literal(Vec3(-s.value, w.value, v.value))
    } else {
        Value.Expr("vec(-(${GrammarSource.of(s)}), ${GrammarSource.of(w)}, ${GrammarSource.of(v)})")
    }

    /** The vector as a per-axis SCALE: the object's x axis is its s axis, so no sign flips (a scale is not a direction). */
    fun toScale(): Value<Vec3> = if (v is Value.Literal && w is Value.Literal && s is Value.Literal) {
        Value.Literal(Vec3(s.value, w.value, v.value))
    } else {
        Value.Expr("vec(${GrammarSource.of(s)}, ${GrammarSource.of(w)}, ${GrammarSource.of(v)})")
    }

    companion object {
        val ZERO = MotionVec(Value.Literal(0.0), Value.Literal(0.0), Value.Literal(0.0))
    }
}

internal object MotionVecs {
    val v = Field.optional("v", Types.number, Value.Literal(0.0), "blocks along the frame's v axis (forward)")
    val w = Field.optional("w", Types.number, Value.Literal(0.0), "blocks along the frame's w axis (up)")
    val s = Field.optional("s", Types.number, Value.Literal(0.0), "blocks along the frame's s axis (v x w, to the right)")
    val axes: Fields = Fields.of("motion-vector", v, w, s)

    val type: FieldType<MotionVec> = object : FieldType<MotionVec> {
        override val label = "{v, w, s} map (each a number, \${param} or '= expr')"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): MotionVec {
            if (raw is String) ctx.fail(path, "write the vector per axis {v, w, s}; each coefficient may be \${param} or '= expr'")
            if (raw is List<*>) ctx.fail(path, "positional vectors are not allowed; write {v: .., w: .., s: ..}")
            val r = axes.read(raw, ctx, path)
            return MotionVec(r[v], r[w], r[s])
        }
        override fun schema(): Map<String, Any?> = axes.jsonSchema()
    }
}

/**
 * A scale written as one number (uniform) or per object axis `{v, w, s}` (the object's local z = v, y = w, x = s). [strict] (an absolute scale)
 * demands all three axes in the map form, because a silently missing axis would be scale 0; a rate (`per_tick`, `accel`) may omit an axis,
 * meaning "does not change along it".
 */
internal class ScaleType(private val strict: Boolean, private val deferMissing: Boolean = false) : FieldType<ScaleVal> {
    override val label = "number, [left, up, forward] or {v, w, s}"

    override fun decode(raw: Any?, ctx: GrammarContext, path: String): ScaleVal {
        if (raw is Map<*, *>) {
            val missing = listOf("v", "w", "s").firstOrNull { it !in raw }
            if (deferMissing) return ScaleVal.PerAxis(MotionVecs.type.decode(raw, ctx, path), missing)
            if (strict) {
                for (axis in listOf("v", "w", "s")) {
                    if (axis !in raw) ctx.fail(path, "an absolute scale written per axis needs all of v, w, s (missing '$axis')")
                }
            }
            return ScaleVal.PerAxis(MotionVecs.type.decode(raw, ctx, path))
        }
        if (raw is List<*>) {
            if (raw.size != 3) ctx.fail(path, "a scale triple is [left, up, forward] (3 numbers, was ${raw.size})")
            val e = raw.mapIndexed { k, x -> Types.number.decode(x, ctx, "$path[$k]") }
            return ScaleVal.PerAxis(MotionVec(v = e[2], w = e[1], s = e[0]), triple = true)
        }
        return ScaleVal.Uniform(Types.number.decode(raw, ctx, path))
    }

    override fun schema(): Map<String, Any?> = linkedMapOf(
        "oneOf" to listOf(
            Types.number.schema(),
            linkedMapOf("type" to "array", "items" to Types.number.schema(), "minItems" to 3, "maxItems" to 3),
            MotionVecs.axes.jsonSchema(),
        ),
    )
}

internal sealed interface ScaleVal {
    data class Uniform(val value: Value<Double>) : ScaleVal
    /** [missing] = the first axis a map form left out; only the `to` field reads it (zero for an offset, an error for a scale). [triple]: written `[left, up, forward]`. */
    data class PerAxis(val vec: MotionVec, val missing: String? = null, val triple: Boolean = false) : ScaleVal

    /** As a track-key scale value; a non-literal uniform number becomes the CEL `vec(s, s, s)`. */
    fun toValue(): Value<Vec3> = when (this) {
        is PerAxis -> vec.toScale()
        is Uniform -> when (val v = value) {
            is Value.Literal -> Value.Literal(Vec3(v.value, v.value, v.value))
            else -> GrammarSource.of(v).let { Value.Expr("vec($it, $it, $it)") }
        }
    }
}

/** Load-time numeric helpers: a rate law is lowered while the file loads, so its numbers must be known then. */
internal class LawNumbers(private val ctx: GrammarContext) {
    fun num(v: Value<Double>, path: String): Double =
        if (v is Value.Expr) ctx.lawNumber(v.source, path)
        else ctx.resolveNumber(v) ?: ctx.fail(path, "a rate law is lowered at load, so this must be a literal, a numeric \${param} with a default, or a '= <CEL>' of constants, params and the copy index i")

    /** A rotation lowered at load: every angle folds to a number (literal, param, `= CEL` of constants and i), so the result is a literal quaternion. */
    fun rotation(r: Rotation, path: String): Quaternion = when (r) {
        is Rotation.Whole -> (r.value as? Value.Literal)?.value ?: ctx.fail(path, "a rate law is lowered at load, so a whole rotation must be a literal [x, y, z, w]")
        is Rotation.Turns -> (Rotation.Turns(r.turns.mapIndexed { k, t -> t.copy(deg = Value.Literal(num(t.deg, "$path[$k].deg"))) }).toValue(ctx, path, ctx.axes) as Value.Literal).value
    }

    fun ticks(v: Value<Double>, path: String): Int {
        val d = num(v, path)
        if (d != floor(d) || d < 0.0 || d > 1_000_000.0) ctx.fail(path, "must be a whole number of ticks >= 0 (was $d)")
        return d.toInt()
    }

    /** A position / offset / velocity {v, w, s} as the local Vec3 (x = left = -s). */
    fun frameVec(v: MotionVec, path: String): Vec3 = Vec3(-num(v.s, "$path.s"), num(v.w, "$path.w"), num(v.v, "$path.v"))

    fun scale(v: ScaleVal, path: String): Vec3 = when (v) {
        is ScaleVal.Uniform -> num(v.value, path).let { Vec3(it, it, it) }
        is ScaleVal.PerAxis -> Vec3(num(v.vec.s, "$path.s"), num(v.vec.w, "$path.w"), num(v.vec.v, "$path.v"))
    }

    /** Rotation vector (axis * degrees, shortest arc) of a quaternion: the TrackKey `turn` convention. */
    fun rotationVector(q: Quaternion): Vec3 {
        val n = q.normalized()
        val s = if (n.w < 0) -1.0 else 1.0
        val sin = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        if (sin < 1e-12) return Vec3(0.0, 0.0, 0.0)
        val deg = Math.toDegrees(2.0 * atan2(sin, n.w * s))
        return Vec3(n.x * s / sin * deg, n.y * s / sin * deg, n.z * s / sin * deg)
    }
}

/** Ease names are checked against [EaseCurves] (the one parser the CEL `ease()` and `every` ranges use); an unknown name is a load error. */
internal object LawEase {
    private const val KNOWN = "linear, in_/out_/in_out_ + sine, quad, cubic, quart, quint, expo, circ, back, elastic, bounce"

    /** The curve itself, for static computation (spacing of `every`); any [EaseCurves] name is fine here. */
    fun curve(name: String, ctx: GrammarContext, path: String): (Double) -> Double =
        EaseCurves.parse(name) ?: ctx.fail(path, "unknown ease '$name' (known: $KNOWN, cubic_bezier(x1,y1,x2,y2), steps(n))")

    /** An ease a law or key can carry: it ends up as the TrackKey ease, which takes the named set only. */
    fun forKey(name: String?, ctx: GrammarContext, path: String, allowStep: Boolean): String? {
        if (name == null) return null
        if (name.trim().lowercase() == "step") {
            if (!allowStep) ctx.fail(path, "ease 'step' is a pose cut, write keys")
            return name
        }
        if (EaseCurves.isSteps(name)) {
            ctx.fail(path, "ease '$name': steps(n) is a pose cut, not a law ease; write keys (steps(n) is valid only in `every` ranges and CEL ease())")
        }
        curve(name, ctx, path)
        if (Easing.fromKey(name) == null) {
            ctx.fail(path, "ease '$name' is a valid curve but a law lowers to key eases (known: $KNOWN; cubic_bezier is not a key ease)")
        }
        return name
    }
}
