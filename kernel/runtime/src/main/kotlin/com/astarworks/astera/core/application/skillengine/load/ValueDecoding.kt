package com.astarworks.astera.core.application.skillengine.load

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skillengine.model.Value

/** Internal control flow of the decoder: carries the first error out of nested readers. Never crosses the public API. */
internal class DecodeAbort(val error: SkillDecodeError) : Exception(error.message)

/** F-7 value forms: literal, `"${param}"` (whole string), `"= <CEL>"`. */
internal object ValueDecoding {
    private val PARAM = Regex("""^\$\{([A-Za-z_][A-Za-z0-9_]*)}$""")
    private const val EXPR_PREFIX = "= "

    fun describe(raw: Any?): String = when (raw) {
        null -> "null"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        is List<*> -> "list"
        is Map<*, *> -> "map"
        else -> raw::class.simpleName ?: "value"
    }

    fun mismatch(skillId: String, path: String, expected: String, raw: Any?): Nothing =
        throw DecodeAbort(SkillDecodeError.TypeMismatch(skillId, path, expected, describe(raw)))

    private fun <T> special(raw: Any?): Value<T>? {
        if (raw !is String) return null
        PARAM.matchEntire(raw)?.let { return Value.ParamRef(it.groupValues[1]) }
        if (raw.startsWith(EXPR_PREFIX)) return Value.Expr(raw.substring(EXPR_PREFIX.length).trim())
        return null
    }

    fun number(raw: Any?, skillId: String, path: String): Value<Double> {
        if (raw is Number) return Value.Literal(raw.toDouble())
        return special<Double>(raw) ?: mismatch(skillId, path, "number, \${param} or '= expr'", raw)
    }

    /** A live value with its declared range: `{expr: "= ...", bound: [lo, hi]}` (11.4). Never legal on a timing field. */
    data class Bounded(val value: Value<Double>, val lo: Double, val hi: Double)

    fun isBoundedForm(raw: Any?): Boolean = raw is Map<*, *> && "expr" in raw && "bound" in raw

    fun bounded(raw: Any?, skillId: String, path: String): Bounded {
        val m = raw as? Map<*, *> ?: mismatch(skillId, path, "{expr, bound}", raw)
        val unknown = m.keys.firstOrNull { it != "expr" && it != "bound" }
        if (unknown != null) throw DecodeAbort(SkillDecodeError.UnknownKey(skillId, path, unknown.toString()))
        val expr = m["expr"] as? String
        if (expr == null || !expr.startsWith(EXPR_PREFIX)) mismatch(skillId, "$path.expr", "'= expr'", m["expr"])
        val b = m["bound"] as? List<*>
        if (b == null || b.size != 2 || b.any { it !is Number }) mismatch(skillId, "$path.bound", "[lo, hi] numbers", m["bound"])
        val lo = (b[0] as Number).toDouble()
        val hi = (b[1] as Number).toDouble()
        if (lo > hi) throw DecodeAbort(SkillDecodeError.Invalid(skillId, "$path.bound", "bound [$lo, $hi] has lo > hi"))
        return Bounded(Value.Expr(expr.substring(EXPR_PREFIX.length).trim()), lo, hi)
    }

    /** A timing field (`at`, `for`, `every`, `times`, `lasts`, `after`, law window): the `{expr, bound}` form is [SkillDecodeError.RuntimeTiming] (C5 rev4.1). */
    fun timingNumber(raw: Any?, skillId: String, path: String, field: String): Value<Double> {
        if (isBoundedForm(raw)) {
            throw DecodeAbort(SkillDecodeError.RuntimeTiming(path, field, "a bound proves a range, not one schedule; write until: / range: instead", skillId))
        }
        return number(raw, skillId, path)
    }

    fun bool(raw: Any?, skillId: String, path: String): Value<Boolean> {
        if (raw is Boolean) return Value.Literal(raw)
        return special<Boolean>(raw) ?: mismatch(skillId, path, "boolean, \${param} or '= expr'", raw)
    }

    fun text(raw: Any?, skillId: String, path: String): Value<String> {
        if (raw !is String) mismatch(skillId, path, "string", raw)
        return special<String>(raw) ?: Value.Literal(raw)
    }

    fun vec3(raw: Any?, skillId: String, path: String): Value<Vec3> {
        if (raw is List<*>) {
            if (raw.size != 3 || raw.any { it !is Number }) mismatch(skillId, path, "[x, y, z] numbers", raw)
            val n = raw.map { (it as Number).toDouble() }
            return Value.Literal(Vec3(n[0], n[1], n[2]))
        }
        return special<Vec3>(raw) ?: mismatch(skillId, path, "[x, y, z], \${param} or '= expr'", raw)
    }

    /** A bare vector literal (point tables, frame offsets). */
    fun literalVec3(raw: Any?, skillId: String, path: String): Vec3 {
        val v = vec3(raw, skillId, path)
        return (v as? Value.Literal)?.value ?: mismatch(skillId, path, "[x, y, z] literal", raw)
    }
}
