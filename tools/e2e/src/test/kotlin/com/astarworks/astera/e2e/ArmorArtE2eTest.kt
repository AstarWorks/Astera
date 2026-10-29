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
                assertThat(wearer.waitUntil(5_000) { holdsIdentity(wearer, 0, id) })
                    .describedAs("give $id into empty inventory (item_id + armor_piece in slot 0)").isTrue()
                rcon("item replace entity ${wearer.name} armor.$slot from entity ${wearer.name} hotbar.0")
                val nbtSlot = when (piece) { "helmet" -> 103; "chestplate" -> 102; "leggings" -> 101; else -> 100 }
                val path = "Inventory[{Slot:${nbtSlot}b}].components.\"minecraft:equippable\""
                // Change just the visual component, keeping Astera's item/armor identity intact.
                rcon("item modify entity ${wearer.name} armor.$slot {type:\"minecraft:set_components\",components:{\"minecraft:equippable\":{slot:\"$slot\",asset_id:\"minecraft:iron\"}}}")
                assertThat(rcon("data get entity ${wearer.name} $path.asset_id")).contains("minecraft:iron")
                wearer.command("astera items refresh")
                assertThat(wearer.waitUntil(5_000) {
                    "astera:$set" in rcon("data get entity ${wearer.name} $path.asset_id")
                }).describedAs("worn asset of $id").isTrue()
                assertThat(rcon("data get entity ${wearer.name} $path.slot")).contains(slot)
                assertThat(holdsIdentity(wearer, nbtSlot, id)).describedAs("worn $id keeps item_id + armor_piece").isTrue()
                E2eReport.notes += "ArmorArt $id: worn $slot asset=astera:$set, armor_piece retained"
            }
        }
    }

    /**
     * Identity is asked with `execute if data` (answer "Test passed"/"Test failed"): the stack's `tooltip_display`
     * hidden_components list alone exceeds the 4096-byte RCON packet, so a `data get` of the whole stack arrives cut
     * off and never contains the custom_data PDC (UI-13, a653735d5; same cure as ItemTooltipStyleE2eTest).
     */
    private fun holdsIdentity(b: Bot, nbtSlot: Int, id: String): Boolean =
        rcon(
            "execute if data entity ${b.name} Inventory[{Slot:${nbtSlot}b,components:{\"minecraft:custom_data\":" +
                "{PublicBukkitValues:{\"astera-items:item_id\":\"$id\",\"astera:armor_piece\":\"$id\"}}}}]",
        ).contains("Test passed")
}
