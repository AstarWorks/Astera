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
            E2eReport.check("Item tooltip", "$id hides vanilla metadata lines", "Paper Inventory minecraft:tooltip_display") {
                val missing = listOf("attribute_modifiers", "enchantments", "unbreakable", "dyed_color").filterNot { hides(b, 0, "minecraft:$it") }
                assertThat(missing).describedAs("not hidden: $missing").isEmpty()
                assertThat(hides(b, 0, "minecraft:lore") || hides(b, 0, "minecraft:custom_name")).describedAs("name/lore hidden").isFalse()
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

    /**
     * RCON answers are one packet (the harness client reads a single one, the server splits at 4096 bytes) and the
     * stored `hidden_components` list alone is longer than that, so `data get` of the display or of the whole
     * components compound arrives cut off. Everything below therefore asks the server yes/no questions with
     * `execute if data` (answer: "Test passed"/"Test failed"), which never grow with the list.
     */
    private fun probe(b: Bot, slot: Int, path: String): Boolean =
        rcon("execute if data entity ${b.name} Inventory[{Slot:${slot}b}]$path").contains("Test passed")

    private fun hides(b: Bot, slot: Int, component: String): Boolean =
        rcon("execute if data entity ${b.name} Inventory[{Slot:${slot}b,components:{\"minecraft:tooltip_display\":" +
            "{hidden_components:[\"$component\"]}}}]").contains("Test passed")

    /**
     * Every vanilla component that can put a line in a tooltip (26.3) plus the ones an Astera stack carries from
     * materialization. Astera's own lines (custom_name / item_name / lore) are deliberately absent.
     */
    private val metaComponents = listOf(
        "attribute_modifiers", "enchantments", "stored_enchantments", "unbreakable", "dyed_color", "trim", "damage",
        "max_damage", "repair_cost", "enchantable", "repairable", "tool", "weapon", "blocks_attacks", "food",
        "consumable", "use_remainder", "use_cooldown", "potion_contents", "potion_duration_scale", "jukebox_playable",
        "banner_patterns", "base_color", "map_id", "map_color", "map_decorations", "firework_explosion", "fireworks",
        "bundle_contents", "container", "container_loot", "charged_projectiles", "written_book_content",
        "writable_book_content", "suspicious_stew_effects", "ominous_bottle_amplifier", "lodestone_tracker",
        "instrument", "equippable", "glider", "death_protection", "damage_resistant", "max_stack_size", "rarity",
        "custom_model_data", "item_model", "profile", "note_block_sound", "painting/variant", "block_state",
        "tooltip_style", "break_sound", "minimum_attack_charge", "damage_type", "piercing_weapon", "kinetic_weapon",
        "swing_animation", "attack_range", "use_effects",
    )

    /** Every Astera stack in the bot's inventory hides each meta component it carries and the core always-hidden ones. */
    private fun assertCovered(b: Bot, label: String, source: String) {
        E2eReport.check("Item tooltip", "$label: tooltip_display covers every present component", source) {
            val checked = mutableListOf<String>()
            for (slot in (0..35) + listOf(100, 101, 102, 103, -106)) {
                if (!probe(b, slot, ".components.\"minecraft:custom_data\".PublicBukkitValues.\"astera-items:item_id\"")) continue
                assertThat(probe(b, slot, ".components.\"minecraft:tooltip_display\"")).describedAs("slot $slot has no tooltip_display").isTrue()
                assertThat(probe(b, slot, ".components.\"minecraft:tooltip_display\"{hide_tooltip:1b}"))
                    .describedAs("slot $slot hide_tooltip would blank the name too").isFalse()
                val present = metaComponents.filter { probe(b, slot, ".components.\"minecraft:$it\"") }
                val missing = present.filterNot { hides(b, slot, "minecraft:$it") }
                assertThat(missing).describedAs("slot $slot components not hidden: $missing (present: $present)").isEmpty()
                // The pickaxe-class baseline must be hidden even when the stack does not carry the component yet.
                val baseline = listOf("attribute_modifiers", "enchantments", "unbreakable", "dyed_color", "trim", "damage")
                    .filterNot { hides(b, slot, "minecraft:$it") }
                assertThat(baseline).describedAs("slot $slot baseline components not hidden: $baseline").isEmpty()
                assertThat(hides(b, slot, "minecraft:lore") || hides(b, slot, "minecraft:custom_name"))
                    .describedAs("slot $slot hides name or lore").isFalse()
                checked += "slot$slot(${present.size})"
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
