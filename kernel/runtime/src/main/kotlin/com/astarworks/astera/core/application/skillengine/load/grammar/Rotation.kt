package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.geometry.math.Quaternion
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * The one place that states which way a positive angle turns (ADR-0303 section 0). The founder decides the "clockwise" reference
 * (camera vs top-down); until then it is this constant and nothing else in the loader mentions a direction.
 */
public object RotationConvention {
    /** `deg > 0` is the right-hand rule: counter-clockwise looking from the axis tip toward the origin. */
    public const val POSITIVE_DEG_IS_COUNTER_CLOCKWISE_FROM_AXIS_TIP: Boolean = true

    /** Sign applied to a magnitude to get the signed `deg` of a CLOCKWISE turn seen from the axis tip (the only cw definition). */
    public const val CW_DEG_SIGN: Double = -1.0

    public fun clockwiseDeg(magnitude: Double): Double = CW_DEG_SIGN * magnitude
}

/** Resolves the axis name of a turn to a unit vector in the frame's LOCAL basis; null = unknown here. */
public fun interface AxisResolver {
    public fun axis(name: String): Vec3?
}

/** `forward back up down left right` in the local frame convention (geometry/math/Basis.kt: x = left, y = up, z = forward). */
public object LocalAxes : AxisResolver {
    override fun axis(name: String): Vec3? = when (name) {
        "forward" -> Vec3(0.0, 0.0, 1.0)
        "back" -> Vec3(0.0, 0.0, -1.0)
        "up" -> Vec3(0.0, 1.0, 0.0)
        "down" -> Vec3(0.0, -1.0, 0.0)
        "left" -> Vec3(1.0, 0.0, 0.0)
        "right" -> Vec3(-1.0, 0.0, 0.0)
        else -> null
    }
}

/** `deg` about a named [axis] of [frame] (null = the frame the field is relative to). `-axis` means the opposite axis. */
public data class AxisTurn(val frame: String?, val axis: String, val deg: Value<Double>)

/**
 * A rotation: a list of [AxisTurn] composed in list order, or a whole quaternion (`[x, y, z, w]`, `${param}`, `= expr`).
 * Composition is `R = R_1 * R_2 * ...` (ADR-0303 3.2: the last turn acts on the object first, the first turns the result).
 */
public sealed interface Rotation {
    public data class Turns(val turns: List<AxisTurn>) : Rotation
    public data class Whole(val value: Value<Quaternion>) : Rotation

    /**
     * The domain quaternion: a [Value.Literal] when every angle is literal; otherwise a CEL product of `quat_axis_angle(vec(..), deg)`
     * terms (CelFunctions "quat_axis_angle", degrees). Axes outside [axes] (e.g. `long`, needing an item frame table) fail the load.
     */
    public fun toValue(ctx: GrammarContext, path: String, axes: AxisResolver = LocalAxes): Value<Quaternion> {
        if (this is Whole) return value
        this as Turns
        val resolved = turns.mapIndexed { i, t ->
            val neg = t.axis.startsWith("-")
            val name = t.axis.removePrefix("-")
            val v = axes.axis(name)
                ?: ctx.fail("$path[$i].axis", "axis '${t.axis}' cannot be resolved here (known: forward back up down left right)")
            (if (neg) v * -1.0 else v) to t.deg
        }
        if (resolved.all { it.second is Value.Literal }) {
            var q = Quaternion.IDENTITY
            for ((axis, deg) in resolved) {
                q = q * Quaternion.axisAngle(axis, Math.toRadians((deg as Value.Literal).value))
            }
            return Value.Literal(q)
        }
        val src = resolved.joinToString(" * ") { (a, deg) ->
            "quat_axis_angle(vec(${a.x}, ${a.y}, ${a.z}), ${GrammarSource.of(deg)})"
        }
        return Value.Expr(src)
    }
}

public object Rotations {
    private val AXIS_NAMES = setOf("forward", "back", "up", "down", "left", "right", "long", "edge", "face")

    public val axisField: Field<String> = Field.required("axis", Types.id, "forward|back|up|down|left|right|long|edge|face, optionally '<frame>.' prefixed and '-' negated")
    public val degField: Field<Value<Double>> = Field.required("deg", Types.number, "degrees, right-handed (see RotationConvention)")
    public val turn: Fields = Fields.of("rotate-step", axisField, degField)

    public val type: FieldType<Rotation> = object : FieldType<Rotation> {
        override val label = "[{axis, deg}, ...], [x, y, z, w], \${param} or '= expr'"

        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Rotation {
            if (raw is String) return Rotation.Whole(ctx.checked(quat(raw, ctx, path), ExprType.QUAT, path))
            val list = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            if (list.size == 4 && list.all { it is Number }) {
                val n = list.map { (it as Number).toDouble() }
                return Rotation.Whole(Value.Literal(Quaternion(n[0], n[1], n[2], n[3]).normalized()))
            }
            val steps = list.mapIndexed { i, step ->
                val p = "$path[$i]"
                val v = turn.read(step, ctx, p)
                val full = v[axisField]
                val frame = full.substringBeforeLast('.', "").ifEmpty { null }
                val axis = full.substringAfterLast('.')
                if (axis.removePrefix("-") !in AXIS_NAMES) ctx.fail("$p.axis", "unknown axis '$full' (expected ${AXIS_NAMES.joinToString("|")})")
                AxisTurn(frame, axis, v[degField])
            }
            return Rotation.Turns(steps)
        }

        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("type" to "array", "items" to turn.jsonSchema()),
                linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "number"), "minItems" to 4, "maxItems" to 4),
                ValueForms.specialSchema(),
            ),
        )
    }

    private fun quat(raw: String, ctx: GrammarContext, path: String): Value<Quaternion> {
        if (!ValueForms.isSpecial(raw)) ctx.mismatch(path, "[{axis, deg}, ...], \${param} or '= expr'", raw)
        // ValueDecoding.text parses the same two special forms for any T; the cast only changes the phantom type.
        @Suppress("UNCHECKED_CAST")
        return ValueDecoding.text(raw, ctx.file, path) as Value<Quaternion>
    }
}
