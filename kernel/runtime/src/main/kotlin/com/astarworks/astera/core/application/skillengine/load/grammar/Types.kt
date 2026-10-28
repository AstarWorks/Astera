package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.ValueDecoding
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.expr.ExprType
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
}
