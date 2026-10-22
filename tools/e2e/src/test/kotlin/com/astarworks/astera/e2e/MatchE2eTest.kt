package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MatchE2eTest : E2eBase() {

    private val suite = "Match"

    @Test
    fun `queue norma starts a match and an op can end it`() {
        val b = bot("Matcher")
        op(b)
        b.waitUntil(5_000) { b.sidebarLines().isNotEmpty() }
        Thread.sleep(1_000)
        val lobbyTitle = b.sidebarTitle()
        val lobbyLines = b.sidebarLines()
        val lobbyWorld = b.worldName
        val lobbyHotbar = b.hotbar()
        val posPacketsBefore = b.positionPackets.get()
        val worldsBefore = b.worldHistory.size
        val chatBefore = b.chat.size
        // norma needs a second player in the queue; this suite brings its own instead of relying on leftovers.
        val partner = bot("Matcher2")
        op(partner)
        partner.command("astera queue join norma")
        b.command("astera queue join norma")
        val joinReply = b.chatSince(chatBefore)

        E2eReport.check(suite, "queue join norma is acknowledged", "SystemChat") {
            assertThat(joinReply).describedAs("no reply to /astera queue join norma").isNotEmpty()
            assertThat(rawKeys(joinReply)).describedAs("raw i18n keys").isEmpty()
            "reply:\n${quote(joinReply)}"
        }

        // queue countdown 10s + in-arena countdown; wait for a teleport.
        val teleported = b.waitUntil(45_000) { b.positionPackets.get() > posPacketsBefore || b.worldHistory.size > worldsBefore }
        b.waitUntil(8_000) { b.sidebarLines() != lobbyLines }
        Thread.sleep(1_500)

        E2eReport.check(suite, "bot is teleported into the match", "ClientboundPlayerPosition / Respawn") {
            assertThat(teleported).describedAs("no teleport within 45s; queue replies=$joinReply").isTrue()
            "worlds=${b.worldHistory}; pos=(${b.x.toInt()},${b.y.toInt()},${b.z.toInt()}); position packets ${posPacketsBefore} -> ${b.positionPackets.get()}"
        }
        E2eReport.check(suite, "match sidebar replaces the lobby sidebar", "SetObjective/SetScore") {
            assertThat(b.sidebarLines()).describedAs("empty sidebar in match").isNotEmpty()
            assertThat(b.sidebarTitle() != lobbyTitle || b.sidebarLines() != lobbyLines)
                .describedAs("sidebar identical to the lobby one (title='$lobbyTitle', lines=$lobbyLines)").isTrue()
            "lobby: '$lobbyTitle' ${lobbyLines}\nmatch: '${b.sidebarTitle()}'\n${quote(b.sidebarLines())}"
        }
        E2eReport.check(suite, "lobby kit is absent from hotbar during the match", "SetContainerContent/Slot (inventory)") {
            val hb = b.hotbar()
            val server = serverSlots(b)
            E2eReport.notes += "Match: server-side inventory ids of ${b.name} during the match: $server; client worlds=${b.worldHistory}"
            // HotbarLayout (ADR-0232): the lobby kit is exactly one hotbar item (slot 8); in a match zero menu
            // items may remain (weapons - owned or the loaner kit - are fine).
            val lobbyKitIds = setOf(lobbyHotbar[8]).filter { it != -1 }.toSet()
            assertThat(lobbyKitIds).describedAs("lobby had no kit item in slot 8: $lobbyHotbar").hasSize(1)
            assertThat(hb.filter { it in lobbyKitIds }).describedAs("lobby kit items still in match hotbar: $hb (lobby hotbar was $lobbyHotbar)").isEmpty()
            "hotbar in lobby=$lobbyHotbar; in match=$hb; crafting area=${b.craftingArea()}; server-side: $server; client worlds=${b.worldHistory}"
        }

        val listBefore = b.chat.size
        b.command("astera match list")
        val list = b.chatSince(listBefore, minNew = 2)
        val matchId = list.firstNotNullOfOrNull { Regex("^(\\S+) \\[").find(it)?.groupValues?.get(1) }
        E2eReport.check(suite, "/astera match list shows the running match", "SystemChat") {
            assertThat(rawKeys(list)).describedAs("raw i18n keys").isEmpty()
            assertThat(matchId).describedAs("no match line found; reply=$list").isNotNull()
            "reply:\n${quote(list)}\nmatch id=$matchId"
        }

        val endBefore = b.chat.size
        val posAtEnd = b.positionPackets.get()
        val worldsAtEnd = b.worldHistory.size
        val matchLines = b.sidebarLines()
        if (matchId != null) b.command("astera match end $matchId")
        val endReply = b.chatSince(endBefore)
        val back = b.waitUntil(30_000) { b.positionPackets.get() > posAtEnd || b.worldHistory.size > worldsAtEnd }
        b.waitUntil(5_000) { b.sidebarLines() != matchLines }
        b.waitUntil(5_000) { b.hotbar()[8] != -1 }
        E2eReport.check(suite, "/astera match end returns the bot to the lobby", "SystemChat + sidebar + inventory") {
            assertThat(matchId).describedAs("no match id").isNotNull()
            assertThat(rawKeys(endReply)).describedAs("raw i18n keys").isEmpty()
            assertThat(back).describedAs("no teleport back to the lobby within 30s after match end; sidebar now='${b.sidebarTitle()}' ${b.sidebarLines()}; end reply=$endReply").isTrue()
            assertThat(b.hotbar()[8] != -1).describedAs("lobby compass did not come back in slot 8: ${b.hotbar()}").isTrue()
            "end reply:\n${quote(endReply)}\nsidebar='${b.sidebarTitle()}' world=${b.worldName} (lobby world was $lobbyWorld); hotbar=${b.hotbar()}"
        }
    }
}
