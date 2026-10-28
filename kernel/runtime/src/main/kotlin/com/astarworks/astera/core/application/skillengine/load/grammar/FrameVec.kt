package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.Value

/**
 * A vector written in the axes of a named frame (ADR-0303 section 0): `{forward: 1.2, up: -0.2}`; a missing axis is 0 (stated in
 * [FrameVecs.axes], not hidden). Never a positional triple, so the order cannot be confused. Which frame it is relative to is the
 * business of the field that carries it (`anchor`, `offset` of an anchor, ...); this type only fixes the axes.
 */
public sealed interface FrameVec {
    /** Components given per axis; each may be a literal, `${param}` or CEL. */
    public data class Parts(val forward: Value<Double>, val up: Value<Double>, val left: Value<Double>) : FrameVec

    /** The whole vector given as `${param}` or `= expr` (a vec3 in the frame's local axes). */
    public data class Whole(val value: Value<Vec3>) : FrameVec

    /**
     * The same vector as a domain [Vec3] in the frame's LOCAL convention (`Basis`: x = left, y = up, z = forward;
     * geometry/math/Basis.kt). Literal parts give a literal; otherwise a CEL `vec(left, up, forward)` expression.
     */
    public fun toLocal(): Value<Vec3> = when (this) {
        is Whole -> value
        is Parts -> {
            val l = left; val u = up; val f = forward
            if (l is Value.Literal && u is Value.Literal && f is Value.Literal) {
                Value.Literal(Vec3(l.value, u.value, f.value))
            } else {
                Value.Expr("vec(${GrammarSource.of(l)}, ${GrammarSource.of(u)}, ${GrammarSource.of(f)})")
            }
        }
    }
}

/** CEL source text of a decoded [Value] so literal and non-literal parts can be composed into one expression. */
internal object GrammarSource {
    fun of(v: Value<Double>): String = when (v) {
        is Value.Literal -> v.value.toString()
        is Value.ParamRef -> v.name
        is Value.Expr -> "(${v.source})"
    }
}

public object FrameVecs {
    public val forward: Field<Value<Double>> = Field.optional("forward", Types.number, Value.Literal(0.0), "blocks along the frame's forward axis")
    public val up: Field<Value<Double>> = Field.optional("up", Types.number, Value.Literal(0.0), "blocks along the frame's up axis")
    public val left: Field<Value<Double>> = Field.optional("left", Types.number, Value.Literal(0.0), "blocks along the frame's left axis")

    /** The three axes of every anchor basis (ADR-0303 section 0). Missing axis = 0. */
    public val axes: Fields = Fields.of("frame-vector", forward, up, left)

    /** `{forward, up, left}` map, or `${param}` / `= expr` for the whole vector. */
    public val type: FieldType<FrameVec> = object : FieldType<FrameVec> {
        override val label = "{forward, up, left} map, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): FrameVec {
            if (raw is String) return FrameVec.Whole(ctx.checked(ValueDecoding.vec3(raw, ctx.file, path), ExprType.VEC3, path))
            if (raw is List<*>) ctx.fail(path, "positional vectors are not allowed; write {forward: .., up: .., left: ..}")
            val v = axes.read(raw, ctx, path)
            return FrameVec.Parts(v[forward], v[up], v[left])
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(axes.jsonSchema(), ValueForms.specialSchema()))
    }
}
