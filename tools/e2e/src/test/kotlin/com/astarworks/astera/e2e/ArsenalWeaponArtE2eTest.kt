package com.astarworks.astera.e2e

import java.net.URI
import java.util.zip.ZipInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Server-delivered item components select the same models as the authored arsenal pack. */
class ArsenalWeaponArtE2eTest : E2eBase() {
    @Test
    fun `arsenal gives select custom art while vanilla use items retain native models`() {
        val player = bot("Art")
        val custom = listOf(
            "iron-blade", "war-axe", "spear", "mace", "twin-daggers", "katana", "halberd", "rapier",
            "cleaver", "scythe", "club", "revolver", "smg", "sniper", "harpoon", "grappling-hook",
            "throwing-knives", "sticky-bomb", "venom-flask", "smoke-bomb", "delay-mine", "frag-grenade",
            "flash-bang", "shock-wand", "ember-rod", "frost-rod", "fireball-charge", "glacier-wand",
            "sanctuary-staff", "chain-rod", "gale-fan",
        )
        val model3d = listOf(
            "iron-blade", "war-axe", "spear", "mace", "twin-daggers", "katana", "halberd", "rapier", "cleaver", "scythe", "club",
        )
        val flat = custom - model3d.toSet()
        op(player)
        assertThat(player.waitUntil(10_000) { player.packPushes.isNotEmpty() }).isTrue()
        val connection = URI(player.packPushes.first().url).toURL().openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 20_000
        }
        val assets = mutableMapOf<String, ByteArray>()
        ZipInputStream(connection.getInputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name.startsWith("assets/astera/items/") || entry.name.startsWith("assets/astera/models/") ||
                    entry.name.startsWith("assets/astera/textures/generated/items/")) {
                    assets[entry.name] = zip.readBytes()
                }
            }
        }
        E2eReport.check("Arsenal art", "all 31 weapon definitions resolve to their intended real model", "pushed pack ZIP") {
            assertThat(model3d + flat).describedAs("every weapon is classified exactly once").containsExactlyInAnyOrderElementsOf(custom)
            // Studio override (ResourcePackBuilder.addGeneratedItemDefinitions): a hand-built content/models/items/<id> model replaces the generated icon.
            for (id in model3d) {
                val definition = assets.getValue("assets/astera/items/$id.json").toString(Charsets.UTF_8)
                assertThat(definition).describedAs("$id definition").contains("\"astera:items/$id\"").doesNotContain("generated/items")
                val tints = Regex("\"minecraft:constant\"").findAll(definition).count()
                assertThat(tints).describedAs("$id definition tints").isGreaterThan(0)
                val model = assets.getValue("assets/astera/models/items/$id.json").toString(Charsets.UTF_8)
                assertThat(model).describedAs("$id 3D model").contains("\"elements\"").doesNotContain("astera_tints")
                val used = Regex("\"tintindex\"\\s*:\\s*(\\d+)").findAll(model).map { it.groupValues[1].toInt() }.toList()
                assertThat(used).describedAs("$id tintindex values stay inside its ${tints}-colour palette").allMatch { it < tints }
                // Vanilla-pattern colour policy: textures are vanilla block textures, nothing derived from Mojang PNGs ships.
                val textures = Regex("\"([a-z0-9_]+)\"\\s*:\\s*\"((?:minecraft|astera):[a-z0-9_/]+)\"").findAll(model.substringAfter("\"textures\"").substringBefore("}")).toList()
                assertThat(textures).describedAs("$id textures").isNotEmpty()
                assertThat(textures.map { it.groupValues[2] }).describedAs("$id textures").allMatch { it.startsWith("minecraft:block/") }
            }
            for (id in flat) {
                assertCleanArsenalIcon(id, assets.getValue("assets/astera/textures/generated/items/$id.png"))
                assertThat(assets.getValue("assets/astera/items/$id.json").toString(Charsets.UTF_8))
                    .contains("astera:generated/items/$id")
                assertThat(assets.getValue("assets/astera/models/generated/items/$id.json").toString(Charsets.UTF_8))
                    .contains("minecraft:item/handheld", "astera:generated/items/$id").doesNotContain("\"elements\"")
                assertThat(assets).doesNotContainKey("assets/astera/models/items/$id.json")
            }
            "${model3d.size} tinted 3D models (astera:items/<id>), ${flat.size} generated flat handhelds; every referenced model file exists in the pack"
        }
        for (id in custom) {
            rcon("clear ${player.name}")
            rcon("astera items give ${player.name} $id")
            E2eReport.check("Arsenal art", "$id selects its custom held model", "server item_model component") {
                val selected = rcon("data get entity ${player.name} Inventory[{Slot:0b}].components.\"minecraft:item_model\"")
                assertThat(selected).contains("astera:$id")
                selected
            }
        }
        val native = mapOf(
            "longbow" to "bow", "shortbow" to "bow", "hand-crossbow" to "crossbow",
            "repeater-crossbow" to "crossbow", "shield-bulwark" to "shield",
        )
        for ((id, material) in native) {
            rcon("clear ${player.name}")
            rcon("astera items give ${player.name} $id")
            E2eReport.check("Arsenal art", "$id keeps its native state renderer", "base material and item_model absence") {
                val stack = rcon("data get entity ${player.name} Inventory[{Slot:0b}]")
                assertThat(stack).contains("minecraft:$material").doesNotContain("minecraft:item_model")
                stack
            }
        }
    }
}
