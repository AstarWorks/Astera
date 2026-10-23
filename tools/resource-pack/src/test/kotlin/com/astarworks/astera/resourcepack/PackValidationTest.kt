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
            // Special-model definitions (the skin rig head, ADR-0250) render a built-in model: no model file to find.
            if (json(def).path("model").path("type").asText() != "minecraft:model") continue
            val model = json(def).path("model").path("model").asText()
            assertThat(files).`as`(def).containsKey(modelKey(model))
        }
    }

    @Test
    fun `the skin rig ships its item definition and both core shader stages with matching markers`() {
        assertThat(files).containsKey("assets/astera/items/skinrig.json")
        assertThat(json("assets/astera/items/skinrig.json").path("model").path("model").path("type").asText())
            .isEqualTo("minecraft:player_head")
        val vsh = String(files.getValue("assets/minecraft/shaders/core/entity.vsh"))
        val fsh = String(files.getValue("assets/minecraft/shaders/core/entity.fsh"))
        // The vertex stage writes the part id at location 8 and the fragment stage reads the same slot.
        assertThat(vsh).contains("layout(location = 8) flat out int rigPart;")
        assertThat(fsh).contains("layout(location = 8) flat in int rigPart;")
        assertThat(vsh).contains("UV2.x == 240")
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
    fun `every bitmap font provider file in any packed font exists in the pack`() {
        val fonts = files.keys.filter { it.startsWith("assets/astera/font/") && it.endsWith(".json") }
        assertThat(fonts).isNotEmpty()
        for (path in fonts) {
            for (provider in json(path).path("providers")) {
                if (provider.path("type").asText() != "bitmap") continue
                val ref = provider.path("file").asText()
                // Font bitmap `file`s keep their extension (`astera:hud/status/atlas.png`), unlike model textures.
                val key = if (ref.startsWith("astera:")) "assets/astera/textures/${ref.removePrefix("astera:")}" else null
                assertThat(key).`as`("$path provider file '$ref' must be astera:-namespaced").isNotNull()
                assertThat(files).`as`("$path provider file $ref").containsKey(key)
            }
        }
    }

    @Test
    fun `texture sources are not shipped`() {
        assertThat(files.keys.filter { it.startsWith("assets/astera/textures/sources/") }).isEmpty()
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
