package com.astarworks.astera.resourcepack

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Integration: actual shipped item YAMLs and PNGs assembled by the real pack builder. */
class ShippedArmorPackTest {
    @Test
    fun `all sixteen armor items resolve inventory art and both worn texture layers`() {
        val content = Path.of(System.getProperty("astera.contentDir"))
        val pack = ResourcePackBuilder(content).collect()
        val json = ObjectMapper()
        val fingerprints = mutableSetOf<List<Byte>>()
        for (set in listOf("leather-scout", "chain-guard", "iron-warden", "diamond-champion")) {
            val equipment = json.readTree(pack.getValue("assets/astera/equipment/$set.json"))
            for (layer in listOf("humanoid", "humanoid_leggings")) {
                assertThat(equipment.path("layers").path(layer)[0].path("texture").asText()).isEqualTo("astera:$set")
                val bytes = pack.getValue("assets/astera/textures/entity/equipment/$layer/$set.png")
                val image = ImageIO.read(ByteArrayInputStream(bytes))
                assertThat(image.width).isEqualTo(64)
                assertThat(image.height).isEqualTo(32)
                if (layer == "humanoid") {
                    assertThat(image.getRGB(4, 20) ushr 24).describedAs("boots leave upper legs visible").isZero()
                    assertThat(image.getRGB(4, 31) ushr 24).isEqualTo(255)
                } else {
                    assertThat(image.getRGB(20, 20) ushr 24).describedAs("leggings leave the upper torso visible").isZero()
                    assertThat(image.getRGB(20, 31) ushr 24).isEqualTo(255)
                }
                assertThat(fingerprints.add(bytes.toList())).describedAs("distinct $set $layer art").isTrue()
            }
            for (piece in listOf("helmet", "chestplate", "leggings", "boots")) {
                val id = "$set-$piece"
                val item = Yaml().load<Map<String, Any>>(Files.readString(content.resolve("items/$id.yaml")))
                assertThat(item["equipment_asset"]).isEqualTo(set)
                assertThat(item["armor_piece"]).isEqualTo(id)
                assertThat(item["equip_slot"]).isEqualTo(piece)
                assertThat(Files.isRegularFile(content.resolve("armor/$id.yaml"))).isTrue()
                assertThat(pack).containsKeys("assets/astera/items/$id.json", "assets/astera/textures/generated/items/$id.png")
            }
        }
        assertThat(pack.keys.none { it.startsWith("assets/astera/textures/sources/") || it.startsWith("assets/astera/textures/equipment/") }).isTrue()
    }
}
