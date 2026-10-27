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
        op(player)
        assertThat(player.waitUntil(10_000) { player.packPushes.isNotEmpty() }).isTrue()
        val connection = URI(player.packPushes.first().url).toURL().openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 20_000
        }
        val assets = mutableMapOf<String, ByteArray>()
        ZipInputStream(connection.getInputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                if (entry.name.startsWith("assets/astera/items/") || entry.name.startsWith("assets/astera/models/")) {
                    assets[entry.name] = zip.readBytes()
                }
            }
        }
        E2eReport.check("Arsenal art", "all 31 weapon definitions resolve to generated flat handheld art", "pushed pack ZIP") {
            for (id in custom) {
                assertThat(assets.getValue("assets/astera/items/$id.json").toString(Charsets.UTF_8))
                    .contains("astera:generated/items/$id")
                assertThat(assets.getValue("assets/astera/models/generated/items/$id.json").toString(Charsets.UTF_8))
                    .contains("minecraft:item/handheld", "astera:generated/items/$id").doesNotContain("\"elements\"")
                assertThat(assets).doesNotContainKey("assets/astera/models/items/$id.json")
            }
            "31 flat handhelds; no retired cuboid models in the pack"
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
