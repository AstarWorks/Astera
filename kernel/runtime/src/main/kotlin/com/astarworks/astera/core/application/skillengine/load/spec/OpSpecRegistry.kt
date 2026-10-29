package com.astarworks.astera.core.application.skillengine.load.spec

import com.astarworks.astera.core.domain.skillengine.op.OpSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec

/** All op specs by name. A duplicate (section, name) is an IllegalStateException at boot (same rule as DefaultOpDecoderRegistry). */
public class OpSpecRegistry(specs: List<OpSpec<*>>) {
    private val all: List<OpSpec<*>> = specs.toList()
    private val bySectionName: Map<Pair<OpSection, String>, OpSpec<*>>

    init {
        val map = LinkedHashMap<Pair<OpSection, String>, OpSpec<*>>()
        for (s in all) {
            val key = s.section to s.name
            check(map.put(key, s) == null) { "duplicate op spec '${s.name}' in section ${s.section}" }
        }
        bySectionName = map
    }

    public fun find(section: OpSection, name: String): OpSpec<*>? = bySectionName[section to name]

    public fun all(): List<OpSpec<*>> = all

    /** `oneOf` over every op (own fields + the shared timing block when the op is timed); feeds content/schemas/skill.schema.json (W1-22). */
    public fun jsonSchema(): JsonObject {
        val variants = all.map { s ->
            val fields = if (s.timing != null) s.fields + TimingSpec.fields else s.fields
            val schema = LinkedHashMap(fields.jsonSchema())
            @Suppress("UNCHECKED_CAST")
            val props = LinkedHashMap(schema["properties"] as Map<String, Any?>)
            props["op"] = linkedMapOf("const" to s.name)
            schema["properties"] = props
            json(schema)
        }
        return JsonObject(mapOf("oneOf" to JsonArray(variants)))
    }

    private fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
        is Iterable<*> -> JsonArray(v.map { json(it) })
        else -> JsonPrimitive(v.toString())
    }
}
