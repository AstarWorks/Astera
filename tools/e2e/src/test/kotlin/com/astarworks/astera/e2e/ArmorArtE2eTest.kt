package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real materializer -> inventory -> worn slot, for all sixteen shipped armor items. */
class ArmorArtE2eTest : E2eBase() {
    @Test
    fun `all arsenal armor keeps its identity and equipment asset when worn and refreshed`() {
        val wearer = bot("ArmorArt")
        op(wearer)
        val pieces = listOf("helmet" to "head", "chestplate" to "chest", "leggings" to "legs", "boots" to "feet")
        for (set in listOf("leather-scout", "chain-guard", "iron-warden", "diamond-champion")) {
            for ((piece, slot) in pieces) {
                val id = "$set-$piece"
                rcon("clear ${wearer.name}")
                wearer.command("astera items give ${wearer.name} $id")
                assertThat(wearer.waitUntil(5_000) {
                    id in rcon("data get entity ${wearer.name} Inventory[{Slot:0b}]")
                }).describedAs("give $id into empty inventory").isTrue()
                val inventory = rcon("data get entity ${wearer.name} Inventory[{Slot:0b}]")
                assertThat(inventory).contains("astera:$id", "astera:armor_piece", id)
                rcon("item replace entity ${wearer.name} armor.$slot from entity ${wearer.name} hotbar.0")
                val nbtSlot = when (piece) { "helmet" -> 103; "chestplate" -> 102; "leggings" -> 101; else -> 100 }
                val path = "Inventory[{Slot:${nbtSlot}b}].components.\"minecraft:equippable\""
                // Change just the visual component, keeping Astera's item/armor identity intact.
                rcon("item modify entity ${wearer.name} armor.$slot {function:\"minecraft:set_components\",components:{\"minecraft:equippable\":{slot:\"$slot\",asset_id:\"minecraft:iron\"}}}")
                assertThat(rcon("data get entity ${wearer.name} $path.asset_id")).contains("minecraft:iron")
                wearer.command("astera items refresh")
                assertThat(wearer.waitUntil(5_000) {
                    "astera:$set" in rcon("data get entity ${wearer.name} $path.asset_id")
                }).describedAs("worn asset of $id").isTrue()
                assertThat(rcon("data get entity ${wearer.name} $path.slot")).contains(slot)
                assertThat(rcon("data get entity ${wearer.name} Inventory[{Slot:${nbtSlot}b}]"))
                    .contains("astera:armor_piece", id)
                E2eReport.notes += "ArmorArt $id: worn $slot asset=astera:$set, armor_piece retained"
            }
        }
    }
}
