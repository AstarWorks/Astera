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
    fun `every rarity ships readable tooltip layers with nine slice metadata`() {
        for (rarity in com.astarworks.astera.core.domain.item.Rarity.entries) {
            for (part in listOf("background", "frame")) {
                val path = "assets/astera/textures/gui/sprites/tooltip/${rarity.name.lowercase()}_$part.png"
                val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(files.getValue(path)))
                val scaling = json("$path.mcmeta").path("gui").path("scaling")
                assertThat(scaling.path("type").asText()).isEqualTo("nine_slice")
                assertThat(scaling.path("width").asInt()).isEqualTo(image.width)
                assertThat(scaling.path("height").asInt()).isEqualTo(image.height)
                assertThat(scaling.path("border").asInt()).isBetween(1, image.width / 2 - 1)
                val centre = image.getRGB(image.width / 2, image.height / 2)
                if (part == "background") {
                    assertThat(centre ushr 24).isBetween(240, 254)
                    assertThat(maxOf(centre shr 16 and 255, centre shr 8 and 255, centre and 255)).isLessThan(24)
                } else {
                    assertThat(centre ushr 24).isZero()
                    assertThat((0 until image.height).any { y ->
                        (0 until image.width).any { x -> image.getRGB(x, y) ushr 24 > 0 }
                    }).isTrue()
                }
            }
        }
    }

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
    fun `vanilla material pilot ships verbatim references without vanilla pixels`() {
        val path = "assets/astera/models/items/verdict-hammer.json"
        assertThat(files.getValue(path)).isEqualTo(
            java.nio.file.Files.readAllBytes(contentDir.resolve("models/items/verdict-hammer.model.json")),
        )
        val refs = json(path).path("textures").elements().asSequence().map { it.asText() }.toList()
        assertThat(refs).anyMatch { it.startsWith("minecraft:block/") }
        for (ref in refs.filter { it.startsWith("minecraft:") }) {
            assertThat(files).doesNotContainKey("assets/minecraft/textures/${ref.removePrefix("minecraft:")}.png")
        }
        assertThat(refs).contains("astera:blocks/verdict-hammer-runes")
        val blockSources = json("assets/minecraft/atlases/blocks.json").path("sources")
            .map { it.path("source").asText() }
        assertThat(blockSources).contains("blocks")
        assertThat(json("assets/astera/items/verdict-hammer.json").path("model").path("model").asText())
            .isEqualTo("astera:items/verdict-hammer")
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
        // The marker the shader tests is exactly the one the server writes, and it is not block light 15, which burning
        // entities, blazes, magma cubes, fireballs and glow item frames are drawn with (any sky value).
        val shaderBlock = Regex("""rigPart = -1;[\s\S]*?if \(UV2\.x == (\d+)\b""").find(vsh)!!.groupValues[1].toInt()
        assertThat(shaderBlock).isEqualTo(com.astarworks.astera.core.domain.animation.skinrig.SkinRigMarker.BLOCK_LIGHT * 16)
        assertThat(shaderBlock).`as`("block light 15 is what burning/emissive entities carry").isNotEqualTo(15 * 16)
        assertThat(vsh).doesNotContain("UV2.x == 240")
        // The light marker is only a candidate (ADR-0298): the item model is mirrored in x and the fragment stage confirms a rig by
        // its winding, so an ordinary entity in a block-14 cell (lantern / torch / glowstone) is never taken for one.
        assertThat(json("assets/astera/items/skinrig.json").path("model").path("transformation").path("scale")[0].asDouble()).isLessThan(0.0)
        assertThat(fsh).contains("rigConfirmed = gl_FrontFacing != (dot(rigViewNormal, rigViewPos) < 0.0);")
        assertThat(fsh).contains("color *= rigConfirmed ? rigLightColor : lightMapColor;")
        assertThat(vsh).contains("layout(location = 11) out vec4 rigLightColor;")
        assertThat(fsh).contains("layout(location = 11) in vec4 rigLightColor;")
        val marker = com.astarworks.astera.core.domain.animation.skinrig.SkinRigMarker
        // All 16 sky payloads the server can write stay within the nibble.
        for (bone in com.astarworks.astera.core.domain.animation.skinrig.SkinRigBone.entries) for (slim in listOf(false, true)) for (ghost in listOf(false, true)) {
            assertThat(marker.skyPayload(bone, slim, ghost)).isBetween(0, 15)
        }
    }

    @Test
    fun `every sound event in sounds json points at a packed Vorbis file and every astera cue resolves to an event`() {
        val events = json("assets/astera/sounds.json")
        assertThat(events.size()).`as`("sound events").isGreaterThanOrEqualTo(17)
        for ((event, def) in events.properties()) {
            assertThat(def.path("sounds")).`as`("sounds of $event").isNotEmpty()
            for (ref in def.path("sounds").map { if (it.isObject) it.path("name").asText() else it.asText() }) {
                assertThat(ref).startsWith("astera:")
                val bytes = files["assets/astera/sounds/${ref.removePrefix("astera:")}.ogg"]
                assertThat(bytes).`as`("file of $event ($ref)").isNotNull()
                assertThat(String(bytes!!.copyOf(4), Charsets.ISO_8859_1)).`as`("$ref is an Ogg stream").isEqualTo("OggS")
            }
        }
        // A cue with its own OGG that names an astera: resource_key is only audible if sounds.json has that event
        // (the two yaml-only registry samples have no OGG and are skipped by the pack builder).
        val yaml = org.yaml.snakeyaml.Yaml()
        java.nio.file.Files.walk(contentDir.resolve("sounds")).use { stream ->
            stream.filter { it.toString().endsWith(".yaml") && java.nio.file.Files.exists(it.resolveSibling(it.fileName.toString().removeSuffix(".yaml") + ".ogg")) }.forEach { file ->
                val key = (yaml.load<Map<String, Any?>>(java.nio.file.Files.readString(file)).orEmpty()["resource_key"] as? String)
                if (key != null && key.startsWith("astera:")) {
                    assertThat(events.has(key.removePrefix("astera:"))).`as`("$file: no sound event for $key").isTrue()
                }
            }
        }
    }

    @Test
    fun `every astera sound id a skill or item plays resolves to a sound event in the pack`() {
        val events = json("assets/astera/sounds.json")
        val yaml = org.yaml.snakeyaml.Yaml()
        // Registry cues: id -> resource_key (a cue without a yaml plays under its own id).
        val resourceKeyById = mutableMapOf<String, String>()
        java.nio.file.Files.walk(contentDir.resolve("sounds")).use { stream ->
            stream.filter { it.toString().endsWith(".yaml") }.forEach { file ->
                val doc = yaml.load<Map<String, Any?>>(java.nio.file.Files.readString(file)).orEmpty()
                val id = doc["id"] as? String ?: return@forEach
                resourceKeyById[id] = doc["resource_key"] as? String ?: id
            }
        }
        val soundId = Regex("""sound_id:\s*['"]?([^'"\s#]+)""")
        var checked = 0
        for (dir in listOf("skills", "items")) {
            java.nio.file.Files.walk(contentDir.resolve(dir)).use { stream ->
                stream.filter { it.toString().endsWith(".yaml") }.forEach { file ->
                    for (m in soundId.findAll(java.nio.file.Files.readString(file))) {
                        val id = m.groupValues[1]
                        val key = resourceKeyById[id] ?: id
                        // Vanilla ids (no astera: namespace) resolve on the client; only pack cues need an event.
                        if (!key.startsWith("astera:")) continue
                        checked++
                        assertThat(events.has(key.removePrefix("astera:")))
                            .`as`("${contentDir.relativize(file)}: sound_id $id -> $key has no sound event in sounds.json").isTrue()
                    }
                }
            }
        }
        assertThat(checked).`as`("astera sound ids referenced by skills/items").isGreaterThan(10)
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
