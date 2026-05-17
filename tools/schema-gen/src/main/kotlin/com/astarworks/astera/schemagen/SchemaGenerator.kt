package com.astarworks.astera.schemagen

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Walks a [SerialDescriptor] recursively and emits a JSON Schema Draft 7 document.
 *
 * Supported kinds:
 *  - STRUCTURE  → `{"type": "object", "properties": {...}, "required": [...]}`
 *  - LIST       → `{"type": "array", "items": <recurse>}`
 *  - MAP        → `{"type": "object", "additionalProperties": <recurse on value>}`
 *  - Primitive  → `{"type": "string|integer|number|boolean"}`
 *  - ENUM       → `{"type": "string", "enum": [...]}`
 *  - Sealed / Polymorphic → `{"oneOf": [...]}` — each registered subclass is inlined.
 *  - CONTEXTUAL → `{"type": "object"}` (fallback)
 */
object SchemaGenerator {

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

    /**
     * Generate a JSON Schema Draft 7 file for the given [serializer].
     *
     * @param serializer  The kotlinx.serialization KSerializer for the root type.
     * @param outputPath  Destination file (created / overwritten).
     * @param schemaId    `$id` URI embedded in the schema header.
     */
    fun <T> generate(
        serializer: KSerializer<T>,
        outputPath: Path,
        schemaId: String,
    ) {
        val root = descriptorToSchema(serializer.descriptor)
        val withMeta = buildJsonObject {
            put("\$schema", "http://json-schema.org/draft-07/schema#")
            put("\$id", schemaId)
            root.forEach { (k, v) -> put(k, v) }
        }
        outputPath.writeText(prettyJson.encodeToString(JsonElement.serializer(), withMeta))
    }

    // -------------------------------------------------------------------------
    // Internal recursive conversion
    // -------------------------------------------------------------------------

    internal fun descriptorToSchema(descriptor: SerialDescriptor): JsonObject {
        return when (val kind = descriptor.kind) {
            // ── Structures (data classes / regular classes) ──────────────────
            StructureKind.CLASS, StructureKind.OBJECT -> buildStructureSchema(descriptor)

            // ── Lists ────────────────────────────────────────────────────────
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", descriptorToSchema(descriptor.getElementDescriptor(0)))
            }

            // ── Maps ─────────────────────────────────────────────────────────
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", descriptorToSchema(descriptor.getElementDescriptor(1)))
            }

            // ── Primitives ───────────────────────────────────────────────────
            is PrimitiveKind -> buildJsonObject {
                put("type", primitiveJsonType(kind))
            }

            // ── Enums ────────────────────────────────────────────────────────
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put(
                    "enum",
                    JsonArray(
                        (0 until descriptor.elementsCount).map { i ->
                            JsonPrimitive(descriptor.getElementName(i))
                        }
                    )
                )
            }

            // ── Sealed / polymorphic ─────────────────────────────────────────
            is PolymorphicKind -> buildPolymorphicSchema(descriptor)

            // ── Contextual / anything else ───────────────────────────────────
            else -> buildJsonObject { put("type", "object") }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildStructureSchema(descriptor: SerialDescriptor): JsonObject {
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<String>()

        for (i in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(i)
            val elemDescriptor = descriptor.getElementDescriptor(i)
            val isOptional = descriptor.isElementOptional(i)

            properties[name] = descriptorToSchema(elemDescriptor)
            if (!isOptional) required += name
        }

        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }
    }

    /**
     * Sealed classes / interfaces in kotlinx.serialization are represented via
     * [PolymorphicKind]. The sealed descriptor has one or two synthetic elements:
     *  element 0 — the type discriminator (usually named "type", kind=STRING)
     *  element 1 — the value payload
     *
     * For SEALED descriptors the value element is itself a structure whose
     * element descriptors are the concrete subclasses. We enumerate them and
     * emit a `oneOf` array. Each variant is inlined with its discriminator
     * property injected so the schema is self-documenting.
     */
    private fun buildPolymorphicSchema(descriptor: SerialDescriptor): JsonObject {
        val variants = mutableListOf<JsonElement>()

        if (descriptor.kind == PolymorphicKind.SEALED) {
            // Element 1 is the "value" slot; its elements are the subclass descriptors.
            val valueDescriptor = descriptor.getElementDescriptor(1)
            for (i in 0 until valueDescriptor.elementsCount) {
                val variantDescriptor = valueDescriptor.getElementDescriptor(i)
                // The element name is the serial name used as the discriminator value.
                val discriminatorValue = valueDescriptor.getElementName(i)
                val variantSchema = descriptorToSchema(variantDescriptor)

                // Inject a literal "type" property with the discriminator value so
                // the schema communicates the expected wire value.
                val withDiscriminator = injectDiscriminator(variantSchema, discriminatorValue)
                variants += withDiscriminator
            }
        }
        // For OPEN polymorphism we can't enumerate subclasses statically — emit
        // an open object schema.
        return if (variants.isEmpty()) {
            buildJsonObject { put("type", "object") }
        } else {
            buildJsonObject { put("oneOf", JsonArray(variants)) }
        }
    }

    /**
     * Clone the given object schema and add a constant `"type"` property
     * whose only allowed value is [discriminatorValue].
     */
    private fun injectDiscriminator(schema: JsonObject, discriminatorValue: String): JsonObject {
        val existingProps = (schema["properties"] as? JsonObject)?.toMap() ?: emptyMap()
        val discriminatorProp = buildJsonObject {
            put("type", "string")
            put("const", discriminatorValue)
        }
        val newProps = JsonObject(mapOf("type" to discriminatorProp) + existingProps)

        val existingRequired = (schema["required"] as? JsonArray)
            ?.map { (it as JsonPrimitive).content } ?: emptyList()
        val newRequired = if ("type" !in existingRequired) listOf("type") + existingRequired else existingRequired

        return buildJsonObject {
            put("type", "object")
            put("properties", newProps)
            if (newRequired.isNotEmpty()) {
                put("required", JsonArray(newRequired.map { JsonPrimitive(it) }))
            }
            // Keep any extra keys (additionalProperties, etc.)
            schema.forEach { (k, v) ->
                if (k !in setOf("type", "properties", "required")) put(k, v)
            }
        }
    }

    private fun primitiveJsonType(kind: PrimitiveKind): String = when (kind) {
        PrimitiveKind.BOOLEAN -> "boolean"
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG -> "integer"
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> "number"
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING -> "string"
    }
}
