package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * ADR-0302: the saved inventory is item ids, rebuilt from the current definition on join. A stack whose derived
 * data is deliberately wrong (a stale tooltip frame stands in for "the definition changed since it was saved")
 * must come back correct after quit + rejoin, while a vanilla stack keeps its material and count.
 */
class ItemIdStorageE2eTest : E2eBase() {
    @Test
    fun `rejoining rebuilds saved items from their id`() {
        val suite = "Item id storage"
        val first = bot("IdStore")
        op(first)
        first.waitUntil(5_000) { first.sidebarLines().isNotEmpty() }
        val name = first.name
        rcon("clear $name")
        rcon("astera items give $name diamond-pickaxe")
        rcon("item replace entity $name hotbar.5 with minecraft:cobblestone 7")
        fun pickaxe(): String = rcon("data get entity $name Inventory[{id:\"minecraft:diamond_pickaxe\"}].components.\"minecraft:tooltip_style\"")
        first.waitUntil(5_000) { pickaxe().contains("astera:rare") }
        // Stale derived data on the live stack: only the item id and the PDC remain truthful.
        rcon("item modify entity $name hotbar.0 {type:\"minecraft:set_components\",components:{\"minecraft:tooltip_style\":\"minecraft:wrong\",\"minecraft:custom_name\":\"stale\"}}")
        assertThat(pickaxe()).contains("minecraft:wrong")

        first.close()
        Thread.sleep(3_000) // the quit saves the id-based snapshot

        val back = bot("IdStore2", PackPolicy.DECLINE, exactName = name)
        back.waitUntil(20_000) { back.sidebarLines().isNotEmpty() }
        val rebuilt = back.waitUntil(10_000) { pickaxe().contains("astera:rare") }
        E2eReport.check(suite, "a saved Astera item is rebuilt from its definition on join", "Inventory minecraft:tooltip_style after rejoin") {
            assertThat(rebuilt).describedAs(pickaxe()).isTrue()
            val nameComponent = rcon("data get entity $name Inventory[{id:\"minecraft:diamond_pickaxe\"}].components.\"minecraft:custom_name\"")
            assertThat(nameComponent).describedAs("stale custom name survived").doesNotContain("stale")
            "diamond-pickaxe -> astera:rare, stale name gone"
        }
        E2eReport.check(suite, "a vanilla stack keeps its material and count", "Inventory cobblestone after rejoin") {
            val stone = rcon("data get entity $name Inventory[{id:\"minecraft:cobblestone\"}].count")
            assertThat(stone).describedAs(stone).contains("7")
            "cobblestone x7"
        }
        E2eReport.check(suite, "no id-storage warnings in the server log", "server log") {
            assertThat(back.disconnectReason).isNull()
            "joined without kick"
        }
    }
}
