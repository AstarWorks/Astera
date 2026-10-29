package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
import com.astarworks.astera.core.domain.skillengine.model.ParamValue
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Value-form recognition shared by every type (the regexes mirror [ValueDecoding], F-7). */
internal object ValueForms {
    const val PARAM_PATTERN = "^\\$\\{[A-Za-z_][A-Za-z0-9_]*}$"
    const val EXPR_PATTERN = "^= .+"
    private val PARAM = Regex(PARAM_PATTERN)

    /** True for `"${param}"` and `"= expr"` strings. */
    fun isSpecial(raw: Any?): Boolean = raw is String && (PARAM.matches(raw) || raw.startsWith("= "))

    /** Schema of a string holding `${param}` or `= CEL`. */
    fun specialSchema(): Map<String, Any?> =
        linkedMapOf("type" to "string", "pattern" to "($PARAM_PATTERN)|($EXPR_PATTERN)")
}

/**
 * An `anchor:` value (ADR-0303 1, R14): a declared anchor id, or `{of, offset: {forward, up, left}, to?, samples?, sweep?}` = a point displaced in that
 * anchor's basis. [sweep] (blocks of anchor travel per call) is meaningful for particles only; other ops reject it.
 * [to] + [samples] (rev4.8) name a segment `of -> to`: the lowering emits [samples] rows per tick at `of + (to - of) * k / (samples - 1)`;
 * particle / particle_def only. Both or neither.
 */
public data class AnchorRef(val of: String, val offset: FrameVec?, val sweep: Double?, val to: String? = null, val samples: Int? = null)

/** The field types of the shared grammar. Every numeric/vector/bool/text type also accepts `${param}` and `= CEL` (ADR-0303 section 0, 11). */
public object Types {
    /** Number of blocks, ticks, degrees ...: literal, `${param}` or `= expr` (compiled to NUM when the context has a compiler). */
    public val number: FieldType<Value<Double>> = object : FieldType<Value<Double>> {
        override val label = "number, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String) =
            ctx.checked(ValueDecoding.number(raw, ctx.file, path), ExprType.NUM, path)
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(linkedMapOf("type" to "number"), ValueForms.specialSchema()))
    }

    public val bool: FieldType<Value<Boolean>> = object : FieldType<Value<Boolean>> {
        override val label = "boolean, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String) =
            ctx.checked(ValueDecoding.bool(raw, ctx.file, path), ExprType.BOOL, path)
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(linkedMapOf("type" to "boolean"), ValueForms.specialSchema()))
    }

    public val text: FieldType<Value<String>> = object : FieldType<Value<String>> {
        override val label = "string"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String) =
            ctx.checked(ValueDecoding.text(raw, ctx.file, path), ExprType.STR, path)
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "string")
    }

    /** A raw vanilla vector `[x, y, z]` in WORLD axes (particle offset, display translation), `${param}` or `= expr`. */
    public val worldVec3: FieldType<Value<Vec3>> = object : FieldType<Value<Vec3>> {
        override val label = "[x, y, z], \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String) =
            ctx.checked(ValueDecoding.vec3(raw, ctx.file, path), ExprType.VEC3, path)
        override fun schema(): Map<String, Any?> = linkedMapOf(
            "oneOf" to listOf(
                linkedMapOf("type" to "array", "items" to linkedMapOf("type" to "number"), "minItems" to 3, "maxItems" to 3),
                ValueForms.specialSchema(),
            ),
        )
    }

    /**
     * An identifier that may also be `${param}` or `= CEL` (a per-index `block: [a, b, c]` under `times` is rewritten to the latter, R28).
     * A literal still has to be a bare identifier; the CEL form is folded at lowering, once `i` is known per copy.
     */
    public val idValue: FieldType<Value<String>> = object : FieldType<Value<String>> {
        override val label = "identifier, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): Value<String> {
            val v = ctx.checked(ValueDecoding.text(raw, ctx.file, path), ExprType.STR, path)
            if (v is Value.Literal) id.decode(v.value, ctx, path)
            return v
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(linkedMapOf("type" to "string", "pattern" to "^\\S+$"), ValueForms.specialSchema()))
    }

    /** A bare identifier: event name, id, ease name. */
    public val id: FieldType<String> = object : FieldType<String> {
        override val label = "identifier"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): String {
            val s = raw as? String ?: ctx.mismatch(path, "identifier", raw)
            if (s.isBlank() || s.any { it.isWhitespace() }) ctx.fail(path, "'$s' is not an identifier")
            return s
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "string", "pattern" to "^\\S+$")
    }

    /**
     * A string fixed when the file loads: a literal as [id], or `"= name"` / `"${name}"` naming a `string` param, read from that param's
     * declared default. Presentation cells that several rows share (a colour, a particle id) read one param this way, so a `skin`
     * patch changes the value once (`params.<name>.default`). Anything else after `= ` is a named load error.
     */
    public val loadText: FieldType<String> = object : FieldType<String> {
        private val paramRef = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")
        private val ident = Regex("[A-Za-z_][A-Za-z0-9_]*")
        override val label = "identifier, '= param' or '\${param}' of a string param"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): String {
            val s = raw as? String ?: ctx.mismatch(path, label, raw)
            // `= "text"` is a param already inlined as a string literal by the param-substitution pass
            if (s.length > 4 && s.startsWith("= \"") && s.endsWith("\"")) return id.decode(s.substring(3, s.length - 1), ctx, path)
            val name = paramRef.matchEntire(s)?.groupValues?.get(1)
                ?: if (s.startsWith("= ")) s.substring(2).trim().also {
                    if (!ident.matches(it)) ctx.fail(path, "'$s' must name one string param ('= name'); a string fixed at load is not an expression")
                } else null
            if (name == null) return id.decode(s, ctx, path)
            val known = ctx.paramDefaults ?: ctx.fail(path, "'$s' reads param '$name', but this context has no param defaults")
            val value = known[name] ?: ctx.fail(path, "'$s' reads param '$name', which is not a declared param with a default")
            return (value as? ParamValue.Text)?.value ?: ctx.fail(path, "param '$name' is not a string param")
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "string", "pattern" to "^\\S+$|^= [A-Za-z_][A-Za-z0-9_]*$")
    }

    public fun enumOf(vararg values: String): FieldType<String> = object : FieldType<String> {
        override val label = "one of ${values.joinToString("|")}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): String {
            val s = raw as? String ?: ctx.mismatch(path, label, raw)
            if (s !in values) ctx.fail(path, "'$s' is not one of ${values.joinToString(", ")}")
            return s
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("enum" to values.toList())
    }

    /** `null` (or an explicit YAML null) is allowed; pair with `default = null` in the spec so absence is written out. */
    public fun <T : Any> nullable(inner: FieldType<T>): FieldType<T?> = object : FieldType<T?> {
        override val label = "${inner.label} or null"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): T? =
            if (raw == null) null else inner.decode(raw, ctx, path)
        override fun schema(): Map<String, Any?> =
            linkedMapOf("anyOf" to listOf(inner.schema(), linkedMapOf("type" to "null")))
    }

    public fun <T> list(item: FieldType<T>): FieldType<List<T>> = object : FieldType<List<T>> {
        override val label = "list of ${item.label}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): List<T> {
            val l = raw as? List<*> ?: ctx.mismatch(path, label, raw)
            return l.mapIndexed { i, x -> item.decode(x, ctx, "$path[$i]") }
        }
        override fun schema(): Map<String, Any?> = linkedMapOf("type" to "array", "items" to item.schema())
    }

    public fun nested(fields: Fields): FieldType<FieldValues> = object : FieldType<FieldValues> {
        override val label = "map (${fields.name})"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String) = fields.read(raw, ctx, path)
        override fun schema(): Map<String, Any?> = fields.jsonSchema()
    }

    private val anchorOf = Field.required("of", id, "the anchor whose axes the numbers are along, read at the row's tick")
    private val anchorVector: Fields = Fields.of("anchor-vector", anchorOf, FrameVecs.forward, FrameVecs.up, FrameVecs.left)
    private val worldX = Field.required("x", number, "blocks along world +x")
    private val worldY = Field.required("y", number, "blocks along world +y")
    private val worldZ = Field.required("z", number, "blocks along world +z")
    private val worldVector: Fields = Fields.of("world-vector", worldX, worldY, worldZ)

    /**
     * A displacement or velocity: `{of: <anchor>, forward, up, left}` (that anchor's axes, missing axis = 0), `{x, y, z}` in world
     * axes, or `${param}` / `= expr` (a world vec3). A positional triple is rejected so the axis order cannot be confused.
     */
    public val offsetVec: FieldType<OffsetVec> = object : FieldType<OffsetVec> {
        override val label = "{of, forward, up, left} map, {x, y, z} map, \${param} or '= expr'"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): OffsetVec {
            if (raw is String) return OffsetVec.World(ctx.checked(ValueDecoding.vec3(raw, ctx.file, path), ExprType.VEC3, path))
            if (raw is List<*>) ctx.fail(path, "positional vectors are not allowed; write {of: <anchor>, forward: .., up: .., left: ..} or {x: .., y: .., z: ..}")
            if (raw is Map<*, *> && "of" in raw) {
                val v = anchorVector.read(raw, ctx, path)
                return OffsetVec.InAnchor(AnchorRef(v[anchorOf], null, null), FrameVec.Parts(v[FrameVecs.forward], v[FrameVecs.up], v[FrameVecs.left]))
            }
            val v = worldVector.read(raw, ctx, path)
            val x = v[worldX]; val y = v[worldY]; val z = v[worldZ]
            return OffsetVec.World(
                if (x is Value.Literal && y is Value.Literal && z is Value.Literal) Value.Literal(Vec3(x.value, y.value, z.value))
                else Value.Expr("vec(${GrammarSource.of(x)}, ${GrammarSource.of(y)}, ${GrammarSource.of(z)})"),
            )
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(anchorVector.jsonSchema(), worldVector.jsonSchema(), ValueForms.specialSchema()))
    }

    private val anchorOffset by lazy { Field.optional("offset", Types.nullable(FrameVecs.type), null, "displacement in the anchor's basis") }
    private val anchorSweep = Field.optional("sweep", Types.nullable(number), null, "blocks of anchor travel per call (particles only)")
    private val anchorTo = Field.optional("to", Types.nullable(id), null, "second end of a segment; the lowering emits `samples` rows along of -> to (particle / particle_def)")
    private val anchorSamples = Field.optional("samples", Types.nullable(number), null, "rows per tick along of -> to, a whole number >= 2 (compile-time constant; with `to`)")
    private val anchorFields by lazy { Fields.of("anchor", anchorOf, anchorOffset, anchorTo, anchorSamples, anchorSweep) }

    /** `anchor: <id>` or `anchor: {of, offset?, to?, samples?, sweep?}`. */
    public val anchorRef: FieldType<AnchorRef> = object : FieldType<AnchorRef> {
        override val label = "anchor id or {of, offset, to, samples, sweep}"
        override fun decode(raw: Any?, ctx: GrammarContext, path: String): AnchorRef = when (raw) {
            is String -> AnchorRef(id.decode(raw, ctx, path), null, null)
            is Map<*, *> -> {
                val v = anchorFields.read(raw, ctx, path)
                val sweep = v[anchorSweep]
                if (sweep != null) {
                    if (sweep !is Value.Literal) ctx.fail("$path.sweep", "sweep must be a literal number of blocks")
                    if (sweep.value <= 0.0) ctx.fail("$path.sweep", "sweep must be > 0")
                }
                val to = v[anchorTo]
                val samplesRaw = v[anchorSamples]
                if (to == null && samplesRaw != null) ctx.fail("$path.samples", "samples needs 'to' (the other end of the segment)")
                if (to != null && samplesRaw == null) ctx.fail("$path.to", "'to' needs samples (rows per tick along of -> to)")
                var samples: Int? = null
                if (samplesRaw != null) {
                    val n = (samplesRaw as? Value.Literal)?.value ?: ctx.fail("$path.samples", "samples must be a literal whole number (compile-time constant)")
                    if (n % 1.0 != 0.0 || n < 2.0) ctx.fail("$path.samples", "samples must be a whole number >= 2 (was $n)")
                    samples = n.toInt()
                }
                AnchorRef(v[anchorOf], v[anchorOffset], (sweep as? Value.Literal)?.value, to, samples)
            }
            else -> ctx.mismatch(path, label, raw)
        }
        override fun schema(): Map<String, Any?> =
            linkedMapOf("oneOf" to listOf(linkedMapOf("type" to "string"), anchorFields.jsonSchema()))
    }
}


/** What [Types.offsetVec] decodes to. */
public sealed interface OffsetVec {
    /** Numbers along the axes of [of] as they stand at the row's tick. */
    public data class InAnchor(val of: AnchorRef, val parts: FrameVec.Parts) : OffsetVec

    /** A vector in world axes: a literal, or a `= expr` / `${param}` the player evaluates. */
    public data class World(val value: Value<Vec3>) : OffsetVec
}
