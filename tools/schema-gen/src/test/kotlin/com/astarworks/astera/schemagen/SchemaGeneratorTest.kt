package com.astarworks.astera.schemagen

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.serializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SchemaGenerator] using minimal in-test data classes
 * so no external DTO is pulled in and the tests are stable.
 */
class SchemaGeneratorTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    @Serializable
    private data class SimpleFoo(
        val x: Int,
        val y: String? = null,
        val z: Double = 1.0,
    )

    @Serializable
    private data class NestedBar(
        val foo: SimpleFoo,
        @SerialName("tags") val tags: List<String> = emptyList(),
        val meta: Map<String, Int> = emptyMap(),
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `simple data class produces object schema with correct types`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<SimpleFoo>().descriptor)

        assertThat(schema["type"]?.toString()).isEqualTo("\"object\"")

        val props = schema["properties"].toString()
        assertThat(props).contains("\"x\"")
        assertThat(props).contains("\"y\"")
        assertThat(props).contains("\"z\"")
        assertThat(props).contains("\"integer\"")   // x: Int
        assertThat(props).contains("\"number\"")    // z: Double
    }

    @Test
    fun `non-optional field appears in required array`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<SimpleFoo>().descriptor)
        val required = schema["required"].toString()

        // x has no default → required
        assertThat(required).contains("\"x\"")
    }

    @Test
    fun `optional field (with default) does NOT appear in required array`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<SimpleFoo>().descriptor)
        val required = schema["required"].toString()

        // y has default null, z has default 1.0 → not required
        assertThat(required).doesNotContain("\"y\"")
        assertThat(required).doesNotContain("\"z\"")
    }

    @Test
    fun `list field produces array schema`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<NestedBar>().descriptor)
        val props = schema["properties"].toString()

        // tags: List<String> → {"type":"array","items":{"type":"string"}}
        assertThat(props).contains("\"array\"")
        assertThat(props).contains("\"items\"")
    }

    @Test
    fun `map field produces object schema with additionalProperties`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<NestedBar>().descriptor)
        val props = schema["properties"].toString()

        assertThat(props).contains("\"additionalProperties\"")
    }

    @Test
    fun `nested struct produces nested object schema`() {
        val schema = SchemaGenerator.descriptorToSchema(serializer<NestedBar>().descriptor)
        val props = schema["properties"].toString()

        // foo is an object → should show nested object properties
        assertThat(props).contains("\"foo\"")
        // The nested object itself has properties x, y, z
        assertThat(props).contains("\"x\"")
    }
}
