package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

class ResourcePackBuilderTest {

    private val contentDir: Path = Path.of(checkNotNull(System.getProperty("astera.contentDir")))
    private val mapper = ObjectMapper()

    private fun json(bytes: ByteArray) = mapper.readTree(bytes)

    @Test
    fun `atlas definitions cover every item and block texture directory the pack writes`() {
        val files = ResourcePackBuilder(contentDir).collect()
        val sources = listOf("items", "blocks").flatMap { atlas ->
            val bytes = files["assets/minecraft/atlases/$atlas.json"] ?: return@flatMap emptyList()
            json(bytes).path("sources").map {
                assertThat(it.path("type").asText()).isEqualTo("directory")
                assertThat(it.path("prefix").asText()).isEqualTo(it.path("source").asText() + "/")
                atlas to it.path("source").asText()
            }
        }
        val covered = sources.map { it.second }
        val textures = files.keys.filter { it.startsWith("assets/astera/textures/") && it.endsWith(".png") }
            .map { it.removePrefix("assets/astera/textures/") }
        for (t in textures) {
            val top = if (t.startsWith("generated/items/")) "generated/items" else t.substringBefore('/')
            if (top in setOf("items", "generated/items", "blocks")) {
                assertThat(covered).`as`(t).contains(top)
            }
        }
        assertThat(covered).contains("generated/items", "items")
    }

    @Test
    fun `atlasDirectories maps item and block texture paths`() {
        val m = ResourcePackBuilder.atlasDirectories(
            listOf("assets/astera/textures/items/a/b.png", "assets/astera/textures/generated/items/x.png",
                "assets/astera/textures/blocks/y.png", "assets/astera/textures/hud/z.png"),
        )
        assertThat(m["items"]).containsExactly("generated/items", "items")
        assertThat(m["blocks"]).containsExactly("blocks")
    }

    @Test
    fun `the real content builds a pack with mcmeta, font, sprites, models and textures`() {
        val files = ResourcePackBuilder(contentDir).collect()

        val mcmeta = json(files.getValue("pack.mcmeta")).path("pack")
        assertThat(mcmeta.path("min_format").map { it.asInt() }).containsExactly(97, 0)
        // 26.3: version.json pack_version.resource_major/minor = 97/1
        assertThat(mcmeta.path("max_format").map { it.asInt() }).containsExactly(97, 1)
        assertThat(mcmeta.has("description")).isTrue()

        val providers = json(files.getValue("assets/astera/font/default.json")).path("providers")
        val types = providers.map { it.path("type").asText() }
        assertThat(types).contains("bitmap", "space")
        assertThat(providers.filter { it.path("type").asText() == "bitmap" }.map { it.path("file").asText() })
            .contains("astera:hud/status/atlas.png", "astera:hud/currency/star-icon.png")
        // Every bitmap provider resolves to a file inside the pack (no dangling references).
        for (provider in providers.filter { it.path("type").asText() == "bitmap" }) {
            val path = "assets/astera/textures/" + provider.path("file").asText().substringAfter("astera:")
            assertThat(files).`as`(path).containsKey(path)
        }

        for (sprite in listOf("heart/full", "heart/half", "heart/container", "heart/absorbing_full", "armor_full")) {
            assertThat(files).containsKey("assets/minecraft/textures/gui/sprites/hud/$sprite.png")
        }
        // content/models is authored in AsteraStudio and changes often, so check
        // the mapping generically: each item definition points at a packed model.
        val itemDefs = files.keys.filter { it.startsWith("assets/astera/items/") && it.endsWith(".json") }
        assertThat(itemDefs).isNotEmpty()
        for (def in itemDefs) {
            val model = json(files.getValue(def)).path("model").path("model").asText()
            assertThat(files).`as`(def).containsKey("assets/astera/models/" + model.substringAfter("astera:") + ".json")
        }
    }

    @Test
    fun `every weapon icon ships with texture, model and item definition`() {
        val files = ResourcePackBuilder(contentDir).collect()
        val weapons = listOf("pistol", "rifle", "shotgun", "knight-sword", "greatsword", "dagger",
            "frost-staff", "fire-staff", "thunder-staff", "verdant-staff")
        for (id in weapons) {
            assertThat(files).containsKey("assets/astera/textures/generated/items/$id.png")
            val model = json(files.getValue("assets/astera/models/generated/items/$id.json"))
            assertThat(model.path("textures").path("layer0").asText()).isEqualTo("astera:generated/items/$id")
            val def = json(files.getValue("assets/astera/items/$id.json")).path("model")
            assertThat(def.path("type").asText()).isEqualTo("minecraft:model")
            assertThat(def.path("model").asText()).isEqualTo("astera:generated/items/$id")
        }
        assertThat(json(files.getValue("assets/astera/models/generated/items/fire-staff.json")).path("parent").asText())
            .isEqualTo("minecraft:item/handheld")
    }

    @Test
    fun `a Studio model named like the item overrides the generated icon`(@TempDir tmp: Path) {
        fun put(rel: String, text: String) {
            val f = tmp.resolve(rel)
            Files.createDirectories(f.parent)
            Files.writeString(f, text)
        }
        put("models/generated/items/dagger.model.json", """{"parent":"minecraft:item/handheld","textures":{"layer0":"astera:generated/items/dagger"}}""")
        put("models/generated/items/pistol.model.json", """{"parent":"minecraft:item/generated","textures":{"layer0":"astera:generated/items/pistol"}}""")
        put("models/items/swords/dagger.model.json", """{"elements":[]}""")
        val files = ResourcePackBuilder(tmp).collect()
        assertThat(json(files.getValue("assets/astera/items/dagger.json")).path("model").path("model").asText())
            .isEqualTo("astera:items/swords/dagger")
        assertThat(json(files.getValue("assets/astera/items/pistol.json")).path("model").path("model").asText())
            .isEqualTo("astera:generated/items/pistol")
    }

    @Test
    fun `the zip is reproducible and the sha1 file matches the zip`(@TempDir tmp: Path) {
        val builder = ResourcePackBuilder(contentDir)
        val first = builder.buildTo(tmp.resolve("a"))
        val second = builder.buildTo(tmp.resolve("b"))

        assertThat(first.sha1).isEqualTo(second.sha1)
        assertThat(Files.readAllBytes(first.zip)).isEqualTo(Files.readAllBytes(second.zip))
        val actual = MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(first.zip)).joinToString("") { "%02x".format(it) }
        assertThat(first.sha1).isEqualTo(actual).matches("[0-9a-f]{40}")
        assertThat(Files.readString(first.zip.resolveSibling("astera.zip.sha1"))).isEqualTo("$actual  astera.zip\n")

        ZipFile(first.zip.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertThat(names).isEqualTo(names.sorted()).hasSize(first.entryCount)
            assertThat(names).first().isEqualTo("assets/astera/font/default.json")
            assertThat(names).contains("pack.mcmeta")
            assertThat(zip.entries().asSequence().map { it.timeLocal.year }.toSet()).containsExactly(1980)
        }
    }

    @Test
    fun `a content change changes the sha1`(@TempDir tmp: Path) {
        val content = tmp.resolve("content")
        Files.createDirectories(content.resolve("textures/items"))
        Files.write(content.resolve("textures/items/a.png"), byteArrayOf(1, 2, 3))
        val before = ResourcePackBuilder(content).buildTo(tmp.resolve("out1")).sha1
        Files.write(content.resolve("textures/items/a.png"), byteArrayOf(1, 2, 4))
        val after = ResourcePackBuilder(content).buildTo(tmp.resolve("out2")).sha1
        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `sounds yaml with an ogg becomes a sound event, a yaml without an ogg is skipped`(@TempDir tmp: Path) {
        val sounds = tmp.resolve("content/sounds")
        Files.createDirectories(sounds.resolve("skills"))
        Files.writeString(sounds.resolve("skills/fireball-cast.yaml"), "id: \"astera:skill/fireball/cast\"\nresource_key: x\n")
        Files.write(sounds.resolve("skills/fireball-cast.ogg"), byteArrayOf(9))
        Files.writeString(sounds.resolve("skills/ghost.yaml"), "id: \"astera:skill/ghost\"\n")
        Files.createDirectories(sounds.resolve("ui"))
        Files.write(sounds.resolve("ui/confirm.ogg"), byteArrayOf(8))

        val files = ResourcePackBuilder(tmp.resolve("content")).collect()

        assertThat(files).containsKey("assets/astera/sounds/skills/fireball-cast.ogg")
        val events = json(files.getValue("assets/astera/sounds.json"))
        assertThat(events.path("skill/fireball/cast").path("sounds").map { it.asText() }).containsExactly("astera:skills/fireball-cast")
        assertThat(events.path("ui/confirm").path("sounds").map { it.asText() }).containsExactly("astera:ui/confirm")
        assertThat(events.has("skill/ghost")).isFalse()
    }

    @Test
    fun `an empty content tree still yields a valid pack with the vanilla overrides`(@TempDir tmp: Path) {
        val files = ResourcePackBuilder(tmp).collect()
        assertThat(files).containsKey("pack.mcmeta")
        assertThat(files.keys.filter { it.startsWith("assets/minecraft/") }).hasSameSizeAs(ResourcePackBuilder.HIDDEN_SPRITES)
        assertThat(files).doesNotContainKey("assets/astera/font/default.json")
    }
}
