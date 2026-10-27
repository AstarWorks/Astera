package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Integration: shipped declarations, art and the production pack builder, with no mocks. */
class ShippedCustomBlockPackTest {
    @Test
    fun `every placed block has unique world art and the identical held and dropped cube`() {
        val content = Path.of(System.getProperty("astera.contentDir"))
        val files = ResourcePackBuilder(content).collect()
        val json = ObjectMapper()
        val states = json.readTree(files.getValue("assets/minecraft/blockstates/note_block.json")).path("variants")
        val claimed = mutableSetOf<String>()
        val pixels = mutableSetOf<List<Int>>()
        val docs = Files.list(content.resolve("blocks")).use { it.filter { f -> f.toString().endsWith(".yaml") }.toList() }
        for (file in docs) {
            val doc = Yaml().load<Map<String, Any?>>(Files.readString(file))
            val visual = doc["visual"] as? Map<*, *> ?: continue
            val state = (visual["state"] as Map<*, *>).entries.associate { it.key.toString() to it.value.toString() }.toSortedMap()
            val key = state.entries.joinToString(",") { "${it.key}=${it.value}" }
            assertThat(claimed.add(key)).describedAs("unique state for $file").isTrue()
            assertThat(state["note"]!!.toInt()).isBetween(1, 9)
            val model = states.path(key).path("model").asText().removePrefix("astera:")
            assertThat(model).startsWith("blocks/")
            val cube = json.readTree(files.getValue("assets/astera/models/$model.json"))
            assertThat(cube.path("parent").asText()).isEqualTo("minecraft:block/cube_all")
            val texture = cube.path("textures").path("all").asText().removePrefix("astera:")
            val image = ImageIO.read(ByteArrayInputStream(files.getValue("assets/astera/textures/$texture.png")))
            assertThat(image.width).isEqualTo(32)
            assertThat(image.height).isEqualTo(32)
            val rgb = image.getRGB(0, 0, 32, 32, null, 0, 32).toList()
            assertThat(pixels.add(rgb)).describedAs("unique art for $file").isTrue()
            assertThat(rgb).noneMatch { it and 0xffffff == 0xff00ff }
            val held = json.readTree(files.getValue("assets/astera/items/${doc["placed_from"]}.json"))
            assertThat(held.path("model").path("model").asText()).isEqualTo("astera:$model")
        }
        assertThat(claimed).hasSize(9)
        assertThat(states.path("instrument=harp,note=0,powered=false").path("model").asText()).isEqualTo("minecraft:block/note_block")
        assertThat(files.keys).noneMatch { it.startsWith("assets/astera/textures/sources/") }
    }
}
