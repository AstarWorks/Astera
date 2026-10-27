package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Founder playtest regressions of the core loop (queue floor, death inside a match).
 *
 * - A PvP lane (`kind: pvp`) never starts with one player: the lone bot is told it is waiting for opponents and no match
 *   appears; a second bot on the other side starts it.
 * - Dying inside a running match goes through the death screen and respawn at the team spawn. The bot is never sent to
 *   the lobby (lobby sidebar) and the match keeps running.
 */
class GameLoopE2eTest : PlaythroughBase() {

    private val suite = "Game loop"

    @Test
    fun `a pvp lane never starts solo and a death in the match respawns in the arena`() {
        val logFrom = logLineCount()
        val a = bot("Gl1", PackPolicy.DECLINE)
        val b = bot("Gl2", PackPolicy.DECLINE)
        listOf(a, b).forEach { bot -> bot.waitUntil(15_000) { bot.sidebarLines().isNotEmpty() && bot.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = a.sidebarTitle()

        // --- the queue floor ------------------------------------------------------------------------------------------
        val chatFrom = a.chat.size
        a.command("astera queue join carry-tnt")
        // The e2e image shortens the queue countdown to a few seconds; wait well past it.
        Thread.sleep(15_000)
        val soloReply = a.chatSince(chatFrom).map { clean(it) }
        E2eReport.check(suite, "a lone player in a PvP lane is told to wait for opponents and no match starts", "SystemChat + RCON astera match list") {
            assertThat(matchLines()).describedAs("a PvP match started with one player: ${matchLines()}").isEmpty()
            assertThat(soloReply.any { it.contains("Waiting for opponents", ignoreCase = true) })
                .describedAs("no waiting-for-opponents notice: $soloReply").isTrue()
            assertThat(rawKeys(soloReply)).describedAs("raw i18n keys").isEmpty()
            quote(soloReply)
        }

        b.command("astera queue join carry-tnt")
        val active = awaitMatch("ACTIVE", 60_000, keepAlive = listOf(a, b))
        E2eReport.check(suite, "the second player completes the roster and the match goes ACTIVE", "RCON astera match list") {
            assertThat(active).describedAs("no ACTIVE match after the second player joined; list=${matchLines()}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)

        // --- death inside the match -----------------------------------------------------------------------------------
        val deathsBefore = b.deaths.size
        rcon("damage ${b.name} 1000 minecraft:magic by ${a.name}")
        val died = b.waitUntil(8_000) { b.deaths.size > deathsBefore }
        val respawnMs = awaitRespawn(b)
        Thread.sleep(1_500)
        E2eReport.check(suite, "dying in a match respawns in the arena, not in the lobby", "SystemChat + RCON playerGameType + sidebar") {
            assertThat(died).describedAs("no death registered for ${b.name}; chat=${b.chatTexts().takeLast(4)}").isTrue()
            assertThat(respawnMs).describedAs("${b.name} never left spectator (game type ${gameTypeOf(b)})").isNotNull()
            assertThat(matchLines()).describedAs("the match must keep running after one death").isNotEmpty()
            assertThat(b.sidebarTitle()).describedAs("victim sees the lobby sidebar after dying: sent to the lobby").isNotEqualTo(lobbyTitle)
            assertThat(b.chatTexts().map { clean(it) }.none { it.contains("Returning to the lobby", ignoreCase = true) })
                .describedAs("out-of-match death message in a match: ${b.chatTexts().takeLast(5)}").isTrue()
            "respawned ${respawnMs} ms after the kill; pos=${posOf(b)}; sidebar='${b.sidebarTitle()}'"
        }

        E2eReport.check(suite, "server log stayed free of errors", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
