package com.astarworks.astera.core.application.skillengine.load.motion

import com.astarworks.astera.core.application.skillengine.expr.cel.EaseCurves
import com.astarworks.astera.core.application.skillengine.load.grammar.FieldType
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVec
import com.astarworks.astera.core.application.skillengine.load.grammar.FrameVecs
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarContext
import com.astarworks.astera.core.application.skillengine.load.grammar.GrammarSource
import com.astarworks.astera.core.application.skillengine.load.grammar.Types
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.model.Value
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A scale written as one number (uniform) or per object axis `{forward, up, left}` (the object's local x = left, y = up, z = forward).
 * [strict] (an absolute scale) demands all three axes in the map form, because a silently missing axis would be scale 0; a rate
 * (`per_tick`, `accel`) may omit an axis, meaning "does not change along it".
 */
internal class ScaleType(private val strict: Boolean) : FieldType<ScaleVal> {
    override val label = "number or {forward, up, left}"

    override fun decode(raw: Any?, ctx: GrammarContext, path: String): ScaleVal {
        if (raw is Map<*, *>) {
            if (strict) {
                for (axis in listOf("forward", "up", "left")) {
                    if (axis !in raw) ctx.fail(path, "an absolute scale written per axis needs all of forward, up, left (missing '$axis')")
                }
            }
            return ScaleVal.PerAxis(FrameVecs.type.decode(raw, ctx, path))
        }
        if (raw is List<*>) ctx.fail(path, "positional vectors are not allowed; write a number or {forward: .., up: .., left: ..}")
        return ScaleVal.Uniform(Types.number.decode(raw, ctx, path))
    }

    override fun schema(): Map<String, Any?> = linkedMapOf(
        "oneOf" to listOf(Types.number.schema(), FrameVecs.axes.jsonSchema()),
    )
}

internal sealed interface ScaleVal {
    data class Uniform(val value: Value<Double>) : ScaleVal
    data class PerAxis(val vec: FrameVec) : ScaleVal

    /** As a track-key scale value; a non-literal uniform number becomes the CEL `vec(s, s, s)`. */
    fun toValue(): Value<Vec3> = when (this) {
        is PerAxis -> vec.toLocal()
        is Uniform -> when (val v = value) {
            is Value.Literal -> Value.Literal(Vec3(v.value, v.value, v.value))
            else -> GrammarSource.of(v).let { Value.Expr("vec($it, $it, $it)") }
        }
    }
}

/** Load-time numeric helpers: a rate law is lowered while the file loads, so its numbers must be known then. */
internal class LawNumbers(private val ctx: GrammarContext) {
    fun num(v: Value<Double>, path: String): Double =
        ctx.resolveNumber(v) ?: ctx.fail(path, "a rate law is lowered at load, so this must be a literal or a numeric \${param} (not a CEL expression)")

    fun ticks(v: Value<Double>, path: String): Int {
        val d = num(v, path)
        if (d != floor(d) || d < 0.0 || d > 1_000_000.0) ctx.fail(path, "must be a whole number of ticks >= 0 (was $d)")
        return d.toInt()
    }

    fun frameVec(v: FrameVec, path: String): Vec3 = when (v) {
        is FrameVec.Parts -> Vec3(num(v.left, "$path.left"), num(v.up, "$path.up"), num(v.forward, "$path.forward"))
        is FrameVec.Whole -> (v.value as? Value.Literal)?.value
            ?: ctx.fail(path, "a rate law is lowered at load, so a whole vector here must be written per axis {forward, up, left}")
    }

    fun scale(v: ScaleVal, path: String): Vec3 = when (v) {
        is ScaleVal.Uniform -> num(v.value, path).let { Vec3(it, it, it) }
        is ScaleVal.PerAxis -> frameVec(v.vec, path)
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
