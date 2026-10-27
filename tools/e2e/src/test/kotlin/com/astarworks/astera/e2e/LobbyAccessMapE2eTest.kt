package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * UI-01..UI-04 (docs/game-design/access-map.md), seen from a client: the plaza shows only the role-hub NPCs,
 * every main-menu tile opens the surface the map names, and a settings toggle answers and reopens the screen.
 */
class LobbyAccessMapE2eTest : E2eBase() {

    private val suite = "Access map"

    /** Opens `/astera <command>` and returns the screen that appeared (waits for its contents). */
    private fun open(b: Bot, command: String): ScreenView {
        // Vanilla kicks a client for chat/command spam (+20 per message, decays 1/tick): pace the commands.
        Thread.sleep(1_100)
        val before = b.screens.size
        b.command("astera $command")
        check(b.waitUntil(5_000) { b.screens.size > before }) { "/astera $command opened nothing; chat=${b.chatTexts().takeLast(3)}" }
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    /** Left-clicks [slot] of [from] and returns the screen that replaced it, or null when none opened. */
    private fun clickOpens(b: Bot, from: ScreenView, slot: Int): ScreenView? {
        val before = b.screens.size
        b.clickSlot(from.containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
        if (!b.waitUntil(4_000) { b.screens.size > before }) return null
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    @Test
    fun `the plaza has only the role hubs and the menu tiles open their screens`() {
        val b = bot("Access")
        Thread.sleep(2_500)

        E2eReport.check(suite, "the spawn plaza shows the Play, Travel and Social hubs and none of the retired NPCs", "ClientboundSetEntityData (villager names)") {
            val names = b.entityEvents.filter { it.added && it.type == "VILLAGER" }
                .flatMap { e -> b.entityTextsById[e.id].orEmpty() }
            assertThat(names.any { "Play" in it }).describedAs("Play hub; villagers=$names").isTrue()
            assertThat(names.any { "Social Hub" in it || "ソーシャル" in it }).describedAs("Social hub; villagers=$names").isTrue()
            assertThat(names.any { "Travel Guide" in it || "旅の案内人" in it }).describedAs("Travel hub; villagers=$names").isTrue()
            val retired = listOf("Queue Master", "Profile Keeper", "Settings Steward", "Party Coordinator", "Gear Keeper")
            assertThat(names.filter { n -> retired.any { it in n } }).describedAs("retired NPCs still spawned").isEmpty()
            "villager names near spawn=$names"
        }

        // Tiles by menu slot (content/gui/main-menu.yaml). Training (11) teleports the player, so it is not clicked here.
        val tiles = linkedMapOf(
            12 to "Daily and Quests", 13 to "Play", 14 to "Shop", 15 to "Vault", 16 to "Cosmetics",
            19 to "Party", 20 to "Friends", 21 to "Achievements", 22 to "Profile", 23 to "Travel", 24 to "Settings",
        )
        E2eReport.check(suite, "/astera menu is a 27-slot menu whose tiles each open a window", "ClientboundOpenScreen") {
            val menu = open(b, "menu")
            val filled = (0..26).filter { b.contents[menu.containerId]?.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            assertThat(rawKeys(listOf(menu.title))).describedAs("raw key as title").isEmpty()
            assertThat(filled).describedAs("menu tiles").containsAll(tiles.keys + listOf(9, 10, 11, 17))
            b.closeWindow(menu.containerId)
            "menu title='${menu.title}' filled slots=$filled"
        }
        for ((slot, name) in tiles) {
            E2eReport.check(suite, "menu tile '$name' (slot $slot) opens a window", "ServerboundContainerClick -> OpenScreen") {
                val menu = open(b, "menu")
                val opened = clickOpens(b, menu, slot)
                assertThat(opened).describedAs("tile $name opened nothing; chat=${b.chatTexts().takeLast(3)}").isNotNull()
                assertThat(rawKeys(listOf(opened!!.title))).describedAs("raw key as title").isEmpty()
                b.closeWindow(opened.containerId)
                "slot $slot -> title='${opened.title}'"
            }
        }
        for ((slot, expect) in mapOf(10 to "/astera help")) {
            E2eReport.check(suite, "menu tile at slot $slot prints its command hint ('$expect')", "ClientboundSystemChat") {
                val menu = open(b, "menu")
                val chatBefore = b.chat.size
                b.clickSlot(menu.containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
                val lines = b.chatSince(chatBefore, minNew = 2)
                assertThat(lines.any { expect in it }).describedAs("no '$expect' in $lines").isTrue()
                assertThat(rawKeys(lines)).describedAs("raw i18n keys in chat").isEmpty()
                "chat=${lines.take(4)}"
            }
        }

        E2eReport.check(suite, "the Daily / Quests tile opens a 54-slot screen with the login bonus tile and the mission and quest headers", "ServerboundContainerClick -> OpenScreen + ContainerSetContent") {
            val menu = open(b, "menu")
            val progress = clickOpens(b, menu, 12)
            assertThat(progress).describedAs("Daily/Quests tile opened nothing; chat=${b.chatTexts().takeLast(3)}").isNotNull()
            assertThat(rawKeys(listOf(progress!!.title))).describedAs("raw key as title").isEmpty()
            val items = b.contents[progress.containerId].orEmpty()
            val filled = (0..53).filter { items.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            // 4 login bonus, 9 missions header, 27 quests header, 45 back, 53 close (ProgressMenu layout).
            assertThat(filled).describedAs("progress tiles").containsAll(listOf(4, 9, 27, 45, 53))
            b.closeWindow(progress.containerId)
            "title='${progress.title}' filled=$filled"
        }

        E2eReport.check(suite, "turning the HP line off in /astera settings stops the status line reaching the client, and on resumes it", "ClientboundSetActionBarText") {
            val settings = open(b, "settings")
            val toggle = { b.clickSlot(settings.containerId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK) }
            val mark = b.actionBars.size
            toggle() // off; the settings screen reopens, so close the new one and let the line settle
            b.waitUntil(4_000) { b.screens.last().containerId != settings.containerId }
            b.closeWindow(b.screens.last().containerId)
            Thread.sleep(1_500)
            val quietFrom = b.actionBars.size
            Thread.sleep(3_000)
            val whileOff = b.hpReadings(quietFrom)
            assertThat(whileOff).describedAs("HP readings after turning the line off (mark=$mark)").isEmpty()
            val settings2 = open(b, "settings")
            b.clickSlot(settings2.containerId, 22, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK) // on again
            b.waitUntil(4_000) { b.screens.last().containerId != settings2.containerId }
            b.closeWindow(b.screens.last().containerId)
            val resumeFrom = b.actionBars.size
            assertThat(b.waitUntil(6_000) { b.hpReadings(resumeFrom).isNotEmpty() }).describedAs("status line did not resume").isTrue()
            "readings while off=${whileOff.size}"
        }

        E2eReport.check(suite, "/astera settings opens the settings screen and the particle toggle answers and reopens it", "ClientboundOpenScreen + chat") {
            val settings = open(b, "settings")
            val filled = (0..26).filter { b.contents[settings.containerId]?.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            assertThat(filled).describedAs("settings tiles").containsAll(listOf(10, 11, 12, 13, 14, 15, 19, 20, 21))
            val chatBefore = b.chat.size
            val screensBefore = b.screens.size
            b.clickSlot(settings.containerId, 19, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)
            val lines = b.chatSince(chatBefore)
            assertThat(lines.any { it.contains("Particles shown") || it.contains("パーティクル") }).describedAs("no toggle answer in $lines").isTrue()
            assertThat(b.waitUntil(4_000) { b.screens.size > screensBefore }).describedAs("settings did not reopen").isTrue()
            b.closeWindow(b.screens.last().containerId)
            "answer=${lines.takeLast(2)}"
        }
    }
}
