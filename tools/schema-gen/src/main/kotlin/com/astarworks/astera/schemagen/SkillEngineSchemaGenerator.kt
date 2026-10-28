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
        return JsonObject(
            linkedMapOf(
                "\$schema" to JsonPrimitive("http://json-schema.org/draft-07/schema#"),
                "\$id" to JsonPrimitive(ID),
                "title" to JsonPrimitive("Skill document (engine v3, ADR-0303)"),
                "definitions" to JsonObject(definitions),
            ),
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
