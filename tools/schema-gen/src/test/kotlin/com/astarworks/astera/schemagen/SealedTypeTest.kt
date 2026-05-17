package com.astarworks.astera.schemagen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Verifies that sealed class hierarchies produce a `oneOf` schema where each
 * variant is an inline object with a constant `"type"` discriminator property.
 */
class SealedTypeTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    @Serializable
    private sealed class Shape {
        @Serializable
        @SerialName("circle")
        data class Circle(val radius: Double) : Shape()

        @Serializable
        @SerialName("rectangle")
        data class Rectangle(val width: Double, val height: Double) : Shape()

        @Serializable
        @SerialName("triangle")
        data class Triangle(val base: Double, val height: Double) : Shape()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `sealed class produces oneOf schema`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<Shape>().descriptor)

        assertThat(schema.containsKey("oneOf"))
            .withFailMessage("Expected top-level 'oneOf' key, got: $schema")
            .isTrue()
    }

    @Test
    fun `oneOf contains exactly three variants`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<Shape>().descriptor)
        val oneOf = schema["oneOf"] as JsonArray

        assertThat(oneOf).hasSize(3)
    }

    @Test
    fun `each variant has a constant type discriminator matching its serial name`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<Shape>().descriptor)
        val oneOf = schema["oneOf"] as JsonArray

        val discriminatorValues = oneOf.map { variant ->
            val obj = variant as JsonObject
            val props = obj["properties"] as JsonObject
            val typeProp = props["type"] as JsonObject
            // "const" holds the serial name value
            typeProp["const"].toString().trim('"')
        }.toSet()

        assertThat(discriminatorValues).containsExactlyInAnyOrder("circle", "rectangle", "triangle")
    }

    @Test
    fun `variant schemas include their specific fields in properties`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<Shape>().descriptor)
        val oneOf = schema["oneOf"] as JsonArray

        val allProperties = oneOf.flatMap { variant ->
            val obj = variant as JsonObject
            val props = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())
            props.keys
        }.toSet()

        // circle has radius; rectangle + triangle have width/height/base
        assertThat(allProperties).contains("radius", "width", "height", "base")
        // Every variant must also have the injected discriminator "type" property
        assertThat(allProperties).contains("type")
    }

    @Test
    fun `type discriminator is required in each variant`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<Shape>().descriptor)
        val oneOf = schema["oneOf"] as JsonArray

        for (variant in oneOf) {
            val obj = variant as JsonObject
            val required = obj["required"] as? JsonArray
            assertThat(required).withFailMessage("variant $obj missing 'required'").isNotNull()
            val requiredNames = required!!.map { it.toString().trim('"') }
            assertThat(requiredNames).contains("type")
        }
    }
}
