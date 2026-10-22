package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Pack integrity over the real content: nothing the pack references is missing, and nothing is
 * oversized. Studio-authored models change often, so these checks are generic.
 */
class PackValidationTest {

    private val contentDir: Path = Path.of(checkNotNull(System.getProperty("astera.contentDir")))
    private val mapper = ObjectMapper()
    private val files: Map<String, ByteArray> = ResourcePackBuilder(contentDir).collect()

    private fun json(path: String): JsonNode = mapper.readTree(files.getValue(path))

    private fun textureKey(ref: String): String? =
        if (ref.startsWith("astera:")) "assets/astera/textures/${ref.removePrefix("astera:")}.png" else null

    private fun modelKey(ref: String): String? =
        if (ref.startsWith("astera:")) "assets/astera/models/${ref.removePrefix("astera:")}.json" else null

    @Test
    fun `every astera texture and parent a packed model references is in the pack`() {
        val models = files.keys.filter { it.startsWith("assets/astera/models/") && it.endsWith(".json") }
        assertThat(models).isNotEmpty()
        for (path in models) {
            val doc = json(path)
            doc.path("parent").asText().takeIf { it.isNotEmpty() }?.let { parent ->
                modelKey(parent)?.let { assertThat(files).`as`("parent of $path").containsKey(it) }
            }
            for (ref in doc.path("textures").elements().asSequence().map { it.asText() }) {
                textureKey(ref)?.let { assertThat(files).`as`("texture $ref of $path").containsKey(it) }
            }
        }
    }

    @Test
    fun `every generated model has an item definition and every definition points at a packed model`() {
        val generated = files.keys.filter { it.startsWith("assets/astera/models/generated/items/") }
            .map { it.substringAfterLast('/').removeSuffix(".json") }
        assertThat(generated).hasSizeGreaterThan(50)
        for (id in generated) assertThat(files).`as`("definition of $id").containsKey("assets/astera/items/$id.json")
        for (def in files.keys.filter { it.startsWith("assets/astera/items/") }) {
            val model = json(def).path("model").path("model").asText()
            assertThat(files).`as`(def).containsKey(modelKey(model))
        }
    }

    @Test
    fun `no file in the pack is oversized`() {
        for ((path, bytes) in files) {
            val limit = when {
                path.endsWith(".png") -> 256 * 1024
                path.endsWith(".ogg") -> 2 * 1024 * 1024
                else -> 512 * 1024
            }
            assertThat(bytes.size).`as`("$path is ${bytes.size} bytes").isLessThanOrEqualTo(limit)
        }
        assertThat(files.values.sumOf { it.size.toLong() }).`as`("uncompressed pack size").isLessThan(32L * 1024 * 1024)
    }

    @Test
    fun `item and gui icons are square and small`() {
        val icons = files.filterKeys { it.startsWith("assets/astera/textures/generated/items/") }
        assertThat(icons).isNotEmpty()
        for ((path, bytes) in icons) {
            val image = javax.imageio.ImageIO.read(bytes.inputStream())
            assertThat(image.width).`as`(path).isEqualTo(image.height)
            assertThat(image.width).`as`(path).isLessThanOrEqualTo(64)
        }
    }
}
