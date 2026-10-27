package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Real Paper materialization and refresh, observed through the stored vanilla component. */
class ItemTooltipStyleE2eTest : E2eBase() {
    @Test
    fun `items carry rarity tooltip styles and refresh repairs the style`() {
        val b = bot("TooltipStyle")
        op(b)
        b.waitUntil(5_000) { b.sidebarLines().isNotEmpty() }
        // Each rarity currently present in shipped content; legendary has art but no item yet.
        val items = mapOf("stone-pickaxe" to "common", "iron-pickaxe" to "uncommon",
            "diamond-pickaxe" to "rare", "orrery-staff" to "epic")
        for ((id, rarity) in items) {
            rcon("clear ${b.name}")
            rcon("astera items give ${b.name} $id")
            fun component(): String = rcon("data get entity ${b.name} Inventory[{Slot:0b}].components.\"minecraft:tooltip_style\"")
            val received = b.waitUntil(5_000) { component().contains("astera:$rarity") }
            E2eReport.check("Item tooltip", "$id uses the $rarity frame", "Paper Inventory minecraft:tooltip_style") {
                assertThat(received).describedAs(component()).isTrue()
                "$id -> astera:$rarity"
            }
            val hidden = rcon("data get entity ${b.name} Inventory[{Slot:0b}].components.\"minecraft:tooltip_display\"")
            E2eReport.check("Item tooltip", "$id hides vanilla metadata lines", "Paper Inventory minecraft:tooltip_display") {
                assertThat(hidden).contains("minecraft:attribute_modifiers", "minecraft:enchantments", "minecraft:unbreakable", "minecraft:dyed_color")
                    .doesNotContain("minecraft:lore", "minecraft:custom_name")
                "$id hides attribute/enchant/unbreakable/dye, keeps name+lore"
            }
            // Preserve the real stack's item PDC while deliberately replacing only the style.
            rcon("item modify entity ${b.name} hotbar.0 {function:\"minecraft:set_components\",components:{\"minecraft:tooltip_style\":\"minecraft:wrong\"}}")
            assertThat(component()).contains("minecraft:wrong")
            rcon("astera items refresh")
            val repaired = b.waitUntil(5_000) { component().contains("astera:$rarity") }
            E2eReport.check("Item tooltip", "$id refresh restores its rarity frame", "items refresh + Inventory component") {
                assertThat(repaired).describedAs(component()).isTrue()
                "$id -> astera:$rarity after refresh"
            }
        }
    }
}
