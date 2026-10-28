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

    /** Top-level keys of an SNBT compound (depth 1; quoted strings and single-quoted strings skipped). */
    private fun topLevelKeys(snbt: String): Set<String> {
        val keys = mutableSetOf<String>()
        var depth = 0
        var i = 0
        while (i < snbt.length) {
            val c = snbt[i]
            if (c == '{' || c == '[') depth++
            else if (c == '}' || c == ']') depth--
            else if (c == '"' || c == '\'') {
                var end = i + 1
                while (end < snbt.length && snbt[end] != c) end += if (snbt[end] == '\\') 2 else 1
                if (c == '"' && depth == 1 && snbt.getOrNull(end + 1) == ':') keys += snbt.substring(i + 1, end)
                i = end
            }
            i++
        }
        return keys
    }

    /** Every Astera stack in the bot's inventory has a tooltip_display hiding each of its components except name and lore. */
    private fun assertCovered(b: Bot, label: String, source: String) {
        E2eReport.check("Item tooltip", "$label: tooltip_display covers every present component", source) {
            val checked = mutableListOf<String>()
            for (slot in (0..35) + listOf(100, 101, 102, 103, -106)) {
                val comps = rcon("data get entity ${b.name} Inventory[{Slot:${slot}b}].components")
                if (!comps.contains("astera")) continue
                val keys = topLevelKeys(comps.substringAfter("components: "))
                if ("minecraft:tooltip_style" !in keys) continue
                val display = rcon("data get entity ${b.name} Inventory[{Slot:${slot}b}].components.\"minecraft:tooltip_display\"")
                assertThat(display).describedAs("slot $slot has no tooltip_display").doesNotContain("Found no elements")
                assertThat(display).describedAs("slot $slot hide_tooltip would blank the name too").doesNotContain("hide_tooltip: 1b")
                val needHidden = keys - setOf("minecraft:custom_name", "minecraft:item_name", "minecraft:lore", "minecraft:tooltip_display")
                val missing = needHidden.filterNot { display.contains(it) }
                assertThat(missing).describedAs("slot $slot components not hidden: $missing").isEmpty()
                checked += "slot$slot(${needHidden.size})"
            }
            assertThat(checked).describedAs("no Astera stack found to check").isNotEmpty()
            "covered: $checked"
        }
    }

    @Test
    fun `every producer path keeps vanilla metadata hidden`() {
        val b = bot("TooltipCover")
        op(b)
        b.waitUntil(5_000) { b.sidebarLines().isNotEmpty() }
        // Join: the starter grant / lobby kit producers.
        b.waitUntil(8_000) { b.contents[0]?.any { it.amount > 0 } == true }
        assertCovered(b, "join (starter grant + lobby kit)", "Inventory components after join")

        // Give command: tools and a staff.
        for (id in listOf("iron-pickaxe", "stone-pickaxe", "orrery-staff")) rcon("astera items give ${b.name} $id")
        assertCovered(b, "give command", "Inventory components after /astera items give")

        // Durability change and a vanilla enchantment: the mutation paths use and skills cause.
        rcon("clear ${b.name}")
        rcon("astera items give ${b.name} iron-pickaxe")
        rcon("astera items give ${b.name} stone-pickaxe")
        rcon("item modify entity ${b.name} container.0 {function:\"minecraft:set_damage\",damage:0.5}")
        rcon("item modify entity ${b.name} container.1 {function:\"minecraft:enchant_with_levels\",levels:5}")
        Thread.sleep(500)
        assertCovered(b, "after durability change and enchant", "Inventory components after set_damage/enchant")

        // A stack saved before the display existed: strip it, the guard must restore it.
        rcon("item modify entity ${b.name} container.0 {function:\"minecraft:set_components\",components:{\"!minecraft:tooltip_display\":{}}}")
        val restored = b.waitUntil(5_000) {
            !rcon("data get entity ${b.name} Inventory[{Slot:0b}].components.\"minecraft:tooltip_display\"").contains("Found no elements")
        }
        E2eReport.check("Item tooltip", "a stack without tooltip_display is repaired by the guard", "Inventory component after strip") {
            assertThat(restored).isTrue()
            "slot 0 tooltip_display restored"
        }
        assertCovered(b, "legacy stack repaired", "Inventory components after strip")
    }
}
