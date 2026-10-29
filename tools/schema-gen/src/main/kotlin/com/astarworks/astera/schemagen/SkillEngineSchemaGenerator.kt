package com.astarworks.astera.schemagen

import com.astarworks.astera.core.application.skillengine.load.SkillEngineSpecs
import com.astarworks.astera.core.application.skillengine.load.grammar.TimingSpec
import com.astarworks.astera.core.application.skillengine.load.motion.MotionLaws
import com.astarworks.astera.core.application.skillengine.load.spec.OpSpecRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * JSON Schema (Draft 7) for the v3 skill engine (ADR-0303), written as `skill.schema.json`.
 *
 * Nothing is listed by hand: [SkillEngineSpecs.all] is the only op list. The `oneOf` over the ops comes from
 * [OpSpecRegistry.jsonSchema] (each op's own fields plus the shared timing block), the timing block from
 * [TimingSpec.fields] and the rate-law family from [MotionLaws.jsonSchema]. Every optional field carries its
 * `default` (taken from the spec's `Field.optional`), so the schema never hides a default.
 */
object SkillEngineSchemaGenerator {

    const val NAME: String = "skill"
    const val ID: String = "https://astera.astarworks.com/schemas/skill.schema.json"

    private val pretty = Json { prettyPrint = true }

    fun schema(): JsonObject {
        val registry = OpSpecRegistry(SkillEngineSpecs.all())
        val definitions = linkedMapOf<String, JsonElement>(
            "op" to registry.jsonSchema(),
            "timing" to TimingSpec.fields.jsonSchema().toJson(),
            "motionLaws" to MotionLaws.jsonSchema().toJson(),
        )
        val root = linkedMapOf<String, JsonElement>(
            "\$schema" to JsonPrimitive("http://json-schema.org/draft-07/schema#"),
            "\$id" to JsonPrimitive(ID),
            "title" to JsonPrimitive("Skill document (engine v3, ADR-0303)"),
        )
        envelope().forEach { (k, v) -> root[k] = v.toJson() }
        root["definitions"] = JsonObject(definitions)
        return JsonObject(root)
    }

    private fun ref(name: String): Map<String, Any?> = linkedMapOf("\$ref" to "#/definitions/$name")

    private fun obj(required: List<String>, properties: Map<String, Any?>): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("type" to "object")
        if (required.isNotEmpty()) m["required"] = required
        m["properties"] = properties
        m["additionalProperties"] = false
        return m
    }

    private val string: Map<String, Any?> = linkedMapOf("type" to "string")
    private val integer: Map<String, Any?> = linkedMapOf("type" to "integer")
    private val celString: Map<String, Any?> = linkedMapOf("type" to "string", "pattern" to "^= .+")
    private val ticks: Map<String, Any?> = linkedMapOf("oneOf" to listOf(linkedMapOf("type" to "integer", "minimum" to 0), celString))
    private fun stringMap(): Map<String, Any?> = linkedMapOf("type" to "object", "additionalProperties" to string)

    /** An op list: a registered op, or the `emit:` / `use:` shorthands of the machine grammar. */
    private fun opList(): Map<String, Any?> = linkedMapOf(
        "type" to "array",
        "items" to linkedMapOf(
            "anyOf" to listOf(
                ref("op"),
                obj(listOf("emit"), linkedMapOf("emit" to string)).minus("additionalProperties"),
                obj(listOf("use"), linkedMapOf("use" to string)).minus("additionalProperties"),
            ),
        ),
    )

    /**
     * The document envelope: the top-level keys `SkillDocumentDecoder` accepts (its `TOP_KEYS`), the `machine` shape of
     * `PartParser.skillMachine`, and the op lists that resolve to `definitions.op`.
     */
    private fun envelope(): Map<String, Any?> {
        val transition = obj(
            listOf("to"),
            linkedMapOf("event" to string, "after" to ticks, "when" to celString, "to" to string, "do" to opList()),
        )
        val state = linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to linkedMapOf(
                "for" to ticks, "then" to string, "final" to linkedMapOf("type" to "boolean"),
                "enter" to opList(), "do" to opList(), "exit" to opList(),
                "on" to linkedMapOf("type" to "array", "items" to transition),
                "use" to string, "with" to linkedMapOf("type" to "object"), "frames" to stringMap(),
            ),
            "additionalProperties" to false,
        )
        val machine = obj(
            listOf("start", "states"),
            linkedMapOf("start" to string, "states" to linkedMapOf("type" to "object", "additionalProperties" to state)),
        )
        val param = obj(
            listOf("type"),
            linkedMapOf(
                "type" to linkedMapOf("enum" to listOf("ticks", "blocks", "number", "bool", "string")),
                "required" to linkedMapOf("type" to "boolean"),
                "default" to linkedMapOf<String, Any?>(),
            ),
        )
        val anchor = obj(
            listOf("id", "anchor"),
            linkedMapOf(
                "id" to string, "anchor" to string, "offset" to linkedMapOf("type" to "object", "additionalProperties" to linkedMapOf("type" to "number")),
                "mount" to string, "mode" to linkedMapOf("enum" to listOf("follow", "snapshot")),
            ),
        )
        val fragment = obj(
            listOf("do"),
            linkedMapOf(
                "params" to linkedMapOf("type" to "object", "additionalProperties" to param),
                "frames_in" to stringMap(),
                "do" to opList(),
            ),
        )
        val i18n = linkedMapOf("type" to "object", "additionalProperties" to string)
        val meta = obj(
            emptyList(),
            linkedMapOf(
                "name" to i18n, "description" to i18n,
                "cooldown_ticks" to linkedMapOf("type" to "integer", "minimum" to 0),
                "resource_type" to linkedMapOf("enum" to listOf("ammo", "energy")),
                "resource_cost" to linkedMapOf("type" to "integer", "minimum" to 0),
                "ego_cost" to linkedMapOf("type" to "integer", "minimum" to 0),
                "reload_ticks" to linkedMapOf("type" to "integer", "minimum" to 0),
                "ready_cue" to linkedMapOf("type" to "boolean"),
                "tags" to linkedMapOf("type" to "array", "items" to string),
            ),
        )
        val envelope = obj(
            listOf("apiVersion", "kind", "id"),
            linkedMapOf(
                "apiVersion" to linkedMapOf("const" to "astera/v3"),
                "extends" to string,
                "with" to linkedMapOf("type" to "object"),
                "kind" to linkedMapOf("const" to "Skill"),
                "id" to linkedMapOf("type" to "string", "minLength" to 1),
                "item" to string,
                "importance" to integer,
                "meta" to meta,
                "params" to linkedMapOf("type" to "object", "additionalProperties" to param),
                "frames_in" to stringMap(),
                "emits" to linkedMapOf("type" to "array", "items" to string),
                "anchors" to linkedMapOf("type" to "array", "items" to anchor),
                "conditions" to linkedMapOf("type" to "object", "additionalProperties" to celString),
                "fragments" to linkedMapOf("type" to "object", "additionalProperties" to fragment),
                "machine" to machine,
            ),
        )
        // a tier (extends:) carries item, meta and with only (ADR-0303 9.3); any other skill carries a machine
        return envelope + linkedMapOf<String, Any?>(
            "if" to linkedMapOf("required" to listOf("extends")),
            "then" to linkedMapOf("required" to listOf("meta"), "not" to linkedMapOf("anyOf" to listOf("machine", "params", "anchors", "fragments", "conditions", "frames_in", "emits", "importance").map { linkedMapOf("required" to listOf(it)) })),
            "else" to linkedMapOf("required" to listOf("machine"), "not" to linkedMapOf("anyOf" to listOf("with").map { linkedMapOf("required" to listOf(it)) })),
        )
    }

    /** Writes `<outputDir>/skill.schema.json` and returns its path. */
    fun generate(outputDir: Path): Path {
        val out = outputDir.resolve("$NAME.schema.json")
        out.writeText(pretty.encodeToString(JsonObject.serializer(), schema()) + "\n")
        return out
    }

    /** The schema helpers return plain maps and lists; this turns them into kotlinx JSON without losing a value. */
    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJson() })
        is Iterable<*> -> JsonArray(map { it.toJson() })
        else -> JsonPrimitive(toString())
    }
}
