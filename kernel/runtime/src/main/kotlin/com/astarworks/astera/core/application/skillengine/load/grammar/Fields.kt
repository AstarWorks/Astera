package com.astarworks.astera.core.application.skillengine.load.grammar

import com.astarworks.astera.core.application.skillengine.load.SkillDecodeError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.skillengine.model.Value

/** How one field is read from a generic YAML tree, and how it shows up in the JSON schema. Same object = same source of truth. */
public interface FieldType<T> {
    /** Short human name used in TypeMismatch messages ("number", "{forward, up, left}", ...). */
    public val label: String

    /** Reads [raw]; aborts through [GrammarContext.fail] / [GrammarContext.mismatch]. Call under [Grammar.guard]. */
    public fun decode(raw: Any?, ctx: GrammarContext, path: String): T

    /** JSON-schema fragment (plain map of strings, numbers, booleans, lists, maps, null). */
    public fun schema(): Map<String, Any?>
}

/**
 * One field of a [Fields] spec. There is no implicit default: a field is [required], or carries the [default] written in the
 * spec (a nullable type with `default = null` is how "absent" is spelled out).
 */
public class Field<T> internal constructor(
    public val name: String,
    public val type: FieldType<T>,
    public val required: Boolean,
    public val default: T?,
    public val doc: String?,
) {
    internal fun schema(): Map<String, Any?> {
        val s = LinkedHashMap(type.schema())
        if (doc != null) s["description"] = doc
        if (!required) {
            s["default"] = when (val d = default) {
                null, is Number, is Boolean, is String -> d
                is Value.Literal<*> -> d.value.let { if (it is Number || it is Boolean || it is String) it else it.toString() }
                else -> d.toString() // domain values print as Kotlin text; the schema only needs to show a default exists
            }
        }
        return s
    }

    public companion object {
        public fun <T> required(name: String, type: FieldType<T>, doc: String? = null): Field<T> =
            Field(name, type, true, null, doc)

        /** [default] is written by the spec author and applied when the key is absent. */
        public fun <T> optional(name: String, type: FieldType<T>, default: T, doc: String? = null): Field<T> =
            Field(name, type, false, default, doc)
    }
}

/** The decoded fields of one map, read back by typed handle. */
public class FieldValues internal constructor(
    private val values: Map<String, Any?>,
    private val present: Set<String>,
    public val spec: Fields,
) {
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(field: Field<T>): T {
        require(field.name in values) { "field '${field.name}' is not part of spec '${spec.name}'" }
        return values[field.name] as T
    }

    /** True when the key was written in the YAML (as opposed to filled from the spec default). */
    public fun isPresent(field: Field<*>): Boolean = field.name in present
}

/**
 * A map schema: a fixed set of [Field]s. An unknown key or a missing required key is a load error naming file, path and field.
 * [jsonSchema] emits the fragment for the same fields (input for the generated skill schema, E10).
 */
public class Fields private constructor(public val name: String, public val fields: List<Field<*>>) {
    init {
        val dup = fields.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "spec '$name' declares duplicate field(s) $dup" }
    }

    /** Merges specs (an op's own fields plus the shared timing block). */
    public operator fun plus(other: Fields): Fields = Fields(name, fields + other.fields)

    /** Reads [raw] (must be a map) at [path]. Call under [Grammar.guard], or use [decode]. */
    public fun read(raw: Any?, ctx: GrammarContext, path: String): FieldValues {
        val map = raw as? Map<*, *> ?: ctx.mismatch(path, "map with keys ${fields.joinToString { it.name }}", raw)
        val known = fields.associateBy { it.name }
        for (key in map.keys) {
            if (key !is String || key !in known) {
                ctx.abort(SkillDecodeError.UnknownKey(ctx.file, path, key.toString()))
            }
        }
        val out = LinkedHashMap<String, Any?>()
        val present = HashSet<String>()
        for (f in fields) {
            @Suppress("UNCHECKED_CAST")
            f as Field<Any?>
            if (f.name in map) {
                present += f.name
                out[f.name] = f.type.decode(map[f.name], ctx, "$path.${f.name}")
            } else if (f.required) {
                ctx.abort(SkillDecodeError.MissingField(ctx.file, path, f.name))
            } else {
                out[f.name] = f.default
            }
        }
        return FieldValues(out, present, this)
    }

    public fun decode(raw: Any?, ctx: GrammarContext, path: String): Result<FieldValues, SkillDecodeError> =
        Grammar.guard { read(raw, ctx, path) }

    /** `{type: object, additionalProperties: false, required: [...], properties: {...}}` for exactly these fields. */
    public fun jsonSchema(): Map<String, Any?> {
        val s = LinkedHashMap<String, Any?>()
        s["type"] = "object"
        s["additionalProperties"] = false
        s["required"] = fields.filter { it.required }.map { it.name }
        s["properties"] = fields.associate { it.name to it.schema() }
        return s
    }

    public companion object {
        public fun of(name: String, vararg fields: Field<*>): Fields = Fields(name, fields.toList())
    }
}
