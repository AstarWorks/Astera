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
    private val icons = listOf(
        "ore-iron", "ore-gold", "ore-diamond", "ore-emerald",
        "reinforced-planks-block", "stone-bulwark-block", "obsidian-plate-block", "packed-clay-block",
        "tempered-glass-block", "hardened-soil-block", "wool-block",
    )

    @Test
    fun `the pushed pack and granted arsenal items select distinct custom art`() {
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
        E2eReport.check("Arsenal art", "all twenty definitions and unique icons ship; nine tools have volume", "pushed pack ZIP") {
            val pixels = mutableSetOf<List<Int>>()
            for (id in tools + icons) {
                val model = if (id in tools) "items/$id" else "items/arsenal/$id"
                val definition = assets.getValue("assets/astera/items/$id.json").toString(Charsets.UTF_8)
                assertThat(definition).describedAs(id).contains("astera:$model")
                val json = assets.getValue("assets/astera/models/$model.json").toString(Charsets.UTF_8)
                if (id in tools) assertThat(json).describedAs(id).contains("\"elements\"", "minecraft:block/")
                else assertThat(json).describedAs(id).contains("minecraft:item/generated", "astera:generated/items/$id")
                val image = ImageIO.read(assets.getValue("assets/astera/textures/generated/items/$id.png").inputStream())
                assertThat(image.width).isEqualTo(32)
                assertThat(image.height).isEqualTo(32)
                val rgba = image.getRGB(0, 0, 32, 32, null, 0, 32).toList()
                assertThat(rgba.any { (it ushr 24) != 0 }).describedAs("empty $id").isTrue()
                assertThat(pixels.add(rgba)).describedAs("duplicate icon $id").isTrue()
            }
            "9 volumetric tool models, 4 currency icons, 7 building icons; all item definitions resolve"
        }
        for (id in tools + icons) {
            rcon("clear ${b.name}")
            rcon("astera items give ${b.name} $id")
            E2eReport.check("Arsenal art", "$id is granted with its custom item model", "server Inventory component") {
                assertThat(b.waitUntil(3_000) {
                    rcon("data get entity ${b.name} Inventory").contains("astera:$id")
                }).describedAs("missing item_model for $id").isTrue()
                "astera:$id"
            }
        }
    }
}
