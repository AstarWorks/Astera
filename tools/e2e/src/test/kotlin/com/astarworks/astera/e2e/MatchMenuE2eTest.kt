package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType
import org.junit.jupiter.api.Test

/**
 * UI-06 (docs/game-design/access-map.md): in a match F opens the 3-button mini menu (Settings / Leave / Help),
 * the match start tells the player about it, Leave asks to confirm and then runs the normal leave-to-lobby flow.
 */
class MatchMenuE2eTest : PlaythroughBase() {

    private val suite = "Match menu"

    private fun click(b: Bot, windowId: Int, slot: Int) =
        b.clickSlot(windowId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK)

    /** Presses F and returns the screen that opened, or null. */
    private fun pressF(b: Bot): ScreenView? {
        val before = b.screens.size
        b.swapHands()
        if (!b.waitUntil(5_000) { b.screens.size > before }) return null
        val s = b.screens.last()
        b.waitUntil(3_000) { b.contents[s.containerId]?.any { it.amount > 0 } == true }
        return s
    }

    @Test
    fun `F in a match opens the mini menu and Leave returns the player to the lobby`() {
        val a = bot("MenuA", PackPolicy.DECLINE)
        val b = bot("MenuB", PackPolicy.DECLINE)
        listOf(a, b).forEach { bot -> bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar()[8] != -1 } }
        val chatFrom = a.chat.size
        a.command("astera queue join carry-tnt")
        b.command("astera queue join carry-tnt")
        val active = awaitMatch("ACTIVE", 60_000, keepAlive = listOf(a, b))
        E2eReport.check(suite, "two queued players get an ACTIVE match", "RCON astera match list") {
            assertThat(active).describedAs("no ACTIVE match; list=${matchLines()}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)
        // the match hotbar carries the locked navigation compass at index 8 (PaperMatchCompass), so slot 8 no longer tells match from lobby: remember it
        val matchSlot8 = a.hotbar()[8]

        E2eReport.check(suite, "the match start tells the player about the F menu", "ClientboundSystemChat") {
            val chat = a.chat.drop(chatFrom).map { clean(it.text) }
            assertThat(chat.any { it.contains("F") && (it.contains("match menu") || it.contains("試合メニュー")) })
                .describedAs("no F-menu hint in chat:\n${quote(chat, 30)}").isTrue()
            assertThat(rawKeys(chat)).describedAs("raw i18n keys").isEmpty()
            "hint seen"
        }

        var menu: ScreenView? = null
        E2eReport.check(suite, "F in a match opens the mini menu with Settings, Leave and Help", "SWAP_HANDS -> OpenScreen") {
            menu = pressF(a)
            val m = checkNotNull(menu) { "F opened nothing; chat=${a.chatTexts().takeLast(3)}" }
            val filled = (0..8).filter { a.contents[m.containerId]?.getOrNull(it)?.amount?.let { n -> n > 0 } == true }
            assertThat(rawKeys(listOf(m.title))).describedAs("raw key as title").isEmpty()
            assertThat(filled).describedAs("menu tiles").containsExactly(2, 4, 6)
            "title='${m.title}' filled=$filled"
        }
        val m = menu ?: return

        E2eReport.check(suite, "Help prints the Norma, stars and weapon-limit lines", "ServerboundContainerClick -> SystemChat") {
            val before = a.chat.size
            click(a, m.containerId, 6)
            val lines = a.chatSince(before, minNew = 3)
            assertThat(lines.size).describedAs("help lines=$lines").isGreaterThanOrEqualTo(3)
            assertThat(rawKeys(lines)).describedAs("raw i18n keys").isEmpty()
            "help=${lines.take(4)}"
        }

        var confirm: ScreenView? = null
        E2eReport.check(suite, "Leave asks to confirm before doing anything", "ServerboundContainerClick -> OpenScreen") {
            Thread.sleep(500)
            val again = checkNotNull(pressF(a)) { "F did not reopen the menu" }
            val before = a.screens.size
            click(a, again.containerId, 4)
            assertThat(a.waitUntil(4_000) { a.screens.size > before }).describedAs("Leave opened no confirm screen").isTrue()
            val cs = a.screens.last()
            confirm = cs
            a.waitUntil(3_000) { a.contents[cs.containerId]?.any { it.amount > 0 } == true }
            assertThat(a.hotbar()[8]).describedAs("the match hotbar changed before confirming: ${a.hotbar()}").isEqualTo(matchSlot8)
            "confirm title='${cs.title}'"
        }
        val c = confirm ?: return

        E2eReport.check(suite, "confirming Leave puts the player back in the lobby with the kit", "inventory + RCON") {
            click(a, c.containerId, 2)
            // A deliberate leave forfeits at once (MODE-14): seat released, match inventory swapped for the lobby kit.
            assertThat(a.waitUntil(30_000) { a.hotbar()[8] != matchSlot8 })
                .describedAs("no lobby kit after Leave; hotbar=${a.hotbar()} chat=${a.chatTexts().takeLast(4)}").isTrue()
            // Every slot (lobby included) lives in the one overworld; "back in the lobby" = left the match slot.
            val local = localPosOf(a)
            assertThat(local).describedAs("no position").isNotNull()
            assertThat(kotlin.math.abs(local!!.first) > 512 || kotlin.math.abs(local.third) > 512)
                .describedAs("still inside the match slot at local $local").isTrue()
            "hotbar=${a.hotbar()} local=$local"
        }
    }
}
