package com.astarworks.astera.e2e.live

import com.astarworks.astera.e2e.E2eReport
import com.astarworks.astera.e2e.rawKeys
import com.astarworks.astera.e2e.visible
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `make live-bots SCENARIO=smoke`: two forwarded bots join the live lobby. */
class SmokeLiveTest : LiveBase() {

    private val suite = "Live smoke"
    private val kitHotbar = listOf(0, 1, 2, 3, 7, 8)

    @Test
    fun `two bots join the live lobby`() {
        val mark = LogMark("lobby")
        val a = bot("smokea")
        val b = bot("smokeb")
        listOf(a, b).forEach { it.waitUntil(10_000) { it.sidebarLines().isNotEmpty() && it.actionBars.isNotEmpty() && it.contents[0]?.any { s -> s.amount > 0 } == true } }
        Thread.sleep(2_500)

        for (p in listOf(a, b)) {
            E2eReport.check(suite, "${p.name} joins the lobby through forwarding and is not kicked", "login / disconnect packet") {
                assertThat(p.joined).describedAs("joined; disconnect=${p.disconnectReason}").isTrue()
                assertThat(p.disconnectReason).isNull()
                val online = rcon("lobby", "list")
                assertThat(online).describedAs("server list does not show the bot").contains(p.name)
                "world=${p.worldName}; pos=(${p.x.toInt()},${p.y.toInt()},${p.z.toInt()}); uuid=${p.uuid}; server: ${online.take(160)}"
            }
            E2eReport.check(suite, "${p.name}: lobby kit is in hotbar slots 0,1,2,3,7,8", "SetContainerContent(inventory)") {
                val hb = p.hotbar()
                val missing = kitHotbar.filter { hb[it] == -1 }
                assertThat(missing).describedAs("kit slots empty: $missing (hotbar=$hb)").isEmpty()
                "hotbar item ids=$hb; crafting area=${p.craftingArea()}"
            }
            E2eReport.check(suite, "${p.name}: sidebar is shown", "SetObjective/SetDisplayObjective/SetScore") {
                val lines = p.sidebarLines()
                assertThat(p.sidebarTitle()).describedAs("no sidebar objective").isNotNull()
                assertThat(lines).describedAs("sidebar empty").isNotEmpty()
                assertThat(rawKeys(lines)).describedAs("raw i18n keys in the sidebar").isEmpty()
                "title='${p.sidebarTitle()}'\n${quote(lines)}"
            }
            E2eReport.check(suite, "${p.name}: HP line is on the action bar", "SetActionBarText / SystemChat(overlay)") {
                assertThat(p.actionBars).describedAs("no action bar message").isNotEmpty()
                val last = p.actionBars.last().text
                assertThat(last).describedAs("HP line blank").isNotBlank()
                assertThat(last).containsPattern("\\d|[\uE000-\uF8FF]")
                "last='${visible(last)}' count=${p.actionBars.size}"
            }
        }

        E2eReport.check(suite, "/astera menu opens a container window", "ClientboundOpenScreen + ContainerSetContent") {
            val before = a.screens.size
            a.command("astera menu")
            assertThat(a.waitUntil(6_000) { a.screens.size > before }).describedAs("no OpenScreen; chat=${a.chatTexts().takeLast(3)}").isTrue()
            val s = a.screens.last()
            a.waitUntil(4_000) { a.contents[s.containerId]?.any { it.amount > 0 } == true }
            val filled = a.contents[s.containerId].orEmpty().count { it.amount > 0 }
            assertThat(rawKeys(listOf(s.title))).describedAs("raw key as title").isEmpty()
            assertThat(filled).describedAs("menu has no items").isGreaterThan(0)
            a.closeWindow(s.containerId)
            "window id=${s.containerId} type=${s.type} title='${s.title}' filled=$filled"
        }

        E2eReport.check(suite, "no raw i18n keys in join-time chat", "SystemChat") {
            val raw = (a.chatTexts() + b.chatTexts()).filter { Regex("<astera\\.[a-z0-9_.-]+>").containsMatchIn(it) }
            assertThat(raw).describedAs("raw translation keys").isEmpty()
            "chat a:\n${quote(a.chatTexts())}\nchat b:\n${quote(b.chatTexts())}"
        }

        E2eReport.check(suite, "lobby server log has no errors/exceptions during the scenario", "server log") {
            val errs = mark.errors()
            assertThat(errs).describedAs("server errors:\n${quote(errs, 15)}").isEmpty()
            "clean (${mark.lines().size} log lines)"
        }
    }
}
