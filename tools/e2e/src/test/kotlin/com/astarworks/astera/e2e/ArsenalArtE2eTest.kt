package com.astarworks.astera.e2e

import java.net.URI
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Checks the actual pushed pack and server item components; visual approval uses the offline/client captures. */
class ArsenalArtE2eTest : E2eBase() {
    private val tools = listOf("stone", "iron", "diamond").flatMap { tier ->
        listOf("pickaxe", "axe", "shovel").map { "$tier-$it" }
    }
    private val ores = listOf("ore-iron", "ore-gold", "ore-diamond", "ore-emerald")
    private val blocks = mapOf(
        "reinforced-planks-block" to "dark_oak_planks", "stone-bulwark-block" to "stone_bricks",
        "obsidian-plate-block" to "obsidian", "packed-clay-block" to "clay",
        "tempered-glass-block" to "glass", "hardened-soil-block" to "rooted_dirt", "wool-block" to "white_wool",
    )

    @Test
    fun `the pushed pack grants flat tools and currencies while blocks retain native cubes`() {
        val b = bot("ArsenalArt")
        op(b)
        assertThat(b.waitUntil(10_000) { b.packPushes.isNotEmpty() }).isTrue()
        val connection = URI(b.packPushes.first().url).toURL().openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 20_000
        }
        val assets = mutableMapOf<String, ByteArray>()
        ZipInputStream(connection.getInputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name.startsWith("assets/astera/items/") ||
                    entry.name.startsWith("assets/astera/models/items/") ||
                    entry.name.startsWith("assets/astera/models/generated/items/") ||
                    entry.name.startsWith("assets/astera/textures/generated/items/")) {
                    assets[entry.name] = zip.readBytes()
                }
            }
        }
        E2eReport.check("Arsenal art", "thirteen flat definitions ship and seven blocks retain vanilla cube models", "pushed pack ZIP") {
            val pixels = mutableSetOf<List<Int>>()
            for (id in tools + ores) {
                val model = "generated/items/$id"
                val definition = assets.getValue("assets/astera/items/$id.json").toString(Charsets.UTF_8)
                assertThat(definition).describedAs(id).contains("astera:$model")
                val json = assets.getValue("assets/astera/models/$model.json").toString(Charsets.UTF_8)
                val parent = if (id in tools) "handheld" else "generated"
                assertThat(json).describedAs(id).contains("minecraft:item/$parent", "astera:generated/items/$id")
                    .doesNotContain("\"elements\"")
                assertThat(assets).doesNotContainKeys(
                    "assets/astera/models/items/$id.json", "assets/astera/models/items/arsenal/$id.json",
                )
                assertCleanArsenalIcon(id, assets.getValue("assets/astera/textures/generated/items/$id.png"))
                val image = ImageIO.read(assets.getValue("assets/astera/textures/generated/items/$id.png").inputStream())
                assertThat(image.width).isEqualTo(32)
                assertThat(image.height).isEqualTo(32)
                val rgba = image.getRGB(0, 0, 32, 32, null, 0, 32).toList()
                assertThat(rgba.any { (it ushr 24) != 0 }).describedAs("empty $id").isTrue()
                assertThat(pixels.add(rgba)).describedAs("duplicate icon $id").isTrue()
            }
            for (id in blocks.keys) {
                assertThat(assets).doesNotContainKeys(
                    "assets/astera/items/$id.json", "assets/astera/models/generated/items/$id.json",
                    "assets/astera/models/items/arsenal/$id.json", "assets/astera/textures/generated/items/$id.png",
                )
            }
            "9 flat handheld tools, 4 generated currency icons; 7 blocks use vanilla cube definitions"
        }
        for (id in tools + ores) {
            rcon("clear ${b.name}")
            rcon("astera items give ${b.name} $id")
            E2eReport.check("Arsenal art", "$id is granted with its custom item model", "server Inventory component") {
                assertThat(b.waitUntil(3_000) {
                    rcon("data get entity ${b.name} Inventory").contains("astera:$id")
                }).describedAs("missing item_model for $id").isTrue()
                "astera:$id"
            }
        }
        for ((id, material) in blocks) {
            rcon("clear ${b.name}")
            rcon("astera items give ${b.name} $id")
            E2eReport.check("Arsenal art", "$id uses the placed block's native cube", "base material and item_model absence") {
                assertThat(b.waitUntil(3_000) {
                    rcon("data get entity ${b.name} Inventory[{Slot:0b}]").contains("minecraft:$material")
                }).isTrue()
                val stack = rcon("data get entity ${b.name} Inventory[{Slot:0b}]")
                assertThat(stack).doesNotContain("minecraft:item_model")
                "minecraft:block/$material in GUI, hand and ground"
            }
        }
    }
}
