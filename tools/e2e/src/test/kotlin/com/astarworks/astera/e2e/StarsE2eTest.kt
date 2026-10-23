package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * In-match stars (founder decision: a star is an item of the match, never carried out of it).
 *
 * Two bots play a Norma match. Generators fire every 3 s in the e2e image (docker/e2e-tune-content.sh). Asserted:
 * the match sidebar has a star line reading 0 at the start; a bot standing on a generator pedestal collects stars
 * (its sidebar count rises) while a bot far from every generator stays at 0; a hit does not change the count; after
 * the match the lobby sidebar has no star line and the wallet (`astera economy balance`) lists no STAR currency.
 */
class StarsE2eTest : PlaythroughBase() {

    private val suite = "Stars"

    /** The number on the sidebar's star line (`* 3 Stars`), or null when the sidebar has no star line. */
    private fun starCount(b: Bot): Int? =
        sidebar(b).firstOrNull { STAR_WORD.containsMatchIn(it) }
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    @Test
    fun `stars are collected at a generator inside the match and nothing survives it`() {
        val a = bot("Sa", PackPolicy.DECLINE)
        val c = bot("Sb", PackPolicy.DECLINE)
        listOf(a, c).forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = a.sidebarTitle()
        val chatFrom = listOf(a, c).associateWith { it.chat.size }
        listOf(a, c).forEach { it.command("astera queue join norma") }

        val active = awaitMatch("ACTIVE", 60_000, keepAlive = listOf(a, c))
        E2eReport.check(suite, "a two-player Norma match goes ACTIVE", "RCON astera match list") {
            assertThat(active).describedAs("match never ACTIVE; chat=${a.chatTexts().takeLast(6)}").isNotNull()
            "line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_500) // generators have fired at least once; nobody stands on a pedestal

        E2eReport.check(suite, "the match sidebar has a star line and it reads 0 for everyone at the start", "SetScore") {
            assertThat(sidebar(a).count { STAR_WORD.containsMatchIn(it) }).describedAs("no star line: ${sidebar(a)}").isEqualTo(1)
            assertThat(starCount(a)).describedAs("sidebar ${sidebar(a)}").isEqualTo(0)
            assertThat(starCount(c)).describedAs("sidebar ${sidebar(c)}").isEqualTo(0)
            assertThat(rawKeys(sidebar(a))).isEmpty()
            quote(sidebar(a))
        }

        // The east generator stands on its pedestal at (6.5, 65, 0.5): teleport bot A onto it, leave bot C at its spawn.
        val spawnA = posOf(a)
        tpArena(a.name, 6.5, 65.0, 0.5, 0f, 0f)
        val collected = a.waitUntil(15_000) { a.nudge(); (starCount(a) ?: 0) > 0 }
        E2eReport.check(suite, "a bot standing on a generator pedestal collects stars (sidebar count rises above 0)", "SetScore") {
            assertThat(collected).describedAs("star count stayed ${starCount(a)}; sidebar=${sidebar(a)}; pos=${posOf(a)}").isTrue()
            "A sidebar: ${sidebar(a)}; pos=${posOf(a)}"
        }
        E2eReport.check(suite, "a bot far from every generator stays at 0", "SetScore") {
            assertThat(starCount(c)).describedAs("far bot's sidebar ${sidebar(c)}; pos=${posOf(c)}").isEqualTo(0)
            "C sidebar: ${sidebar(c)}; pos=${posOf(c)}"
        }

        // Step off the pedestal (back to A's own island) so the count is stable, then hit: a hit never changes stars.
        tpArena(a.name, spawnA?.first ?: -30.0, spawnA?.second ?: 65.0, spawnA?.third ?: 0.0, 0f, 0f)
        Thread.sleep(3_000)
        val before = starCount(a)
        rcon("damage ${c.name} 1 minecraft:player_attack by ${a.name}")
        Thread.sleep(3_000)
        E2eReport.check(suite, "a hit (and the damage it deals) does not change the star count", "SetScore") {
            assertThat(before).describedAs("no star line before the hit: ${sidebar(a)}").isNotNull()
            assertThat(starCount(a)).describedAs("A: before=$before sidebar=${sidebar(a)}").isEqualTo(before)
            assertThat(starCount(c)).describedAs("C sidebar=${sidebar(c)}").isEqualTo(0)
            "A before=$before after=${starCount(a)}; C=${starCount(c)}"
        }

        // --- the match ends: nothing about stars is visible in the lobby ----------------------------------------------
        endActiveMatches()
        listOf(a, c).forEach { b -> b.waitUntil(40_000) { b.hotbar()[8] != -1 && b.sidebarTitle() == lobbyTitle } }
        Thread.sleep(2_000)
        for (b in listOf(a, c)) {
            E2eReport.check(suite, "${b.name}: the lobby sidebar has no star line", "SetScore") {
                assertThat(b.sidebarTitle()).describedAs("still on the match sidebar: ${sidebar(b)}").isEqualTo(lobbyTitle)
                assertThat(sidebar(b).filter { STAR_WORD.containsMatchIn(it) }).describedAs("lobby sidebar ${sidebar(b)}").isEmpty()
                quote(sidebar(b))
            }
            val from = b.chat.size
            b.command("astera economy balance")
            val reply = b.chatSince(from, minNew = 1).map { clean(it) }
            E2eReport.check(suite, "${b.name}: the wallet lists no STAR currency", "SystemChat astera economy balance") {
                assertThat(reply).describedAs("no balance reply").isNotEmpty()
                assertThat(reply.filter { STAR_WORD.containsMatchIn(it) }).describedAs("wallet reply mentions stars: $reply").isEmpty()
                quote(reply)
            }
            val ending = b.chat.drop(chatFrom.getValue(b)).map { clean(it.text) }
            E2eReport.check(suite, "${b.name}: no chat line after the match grants or mentions stars", "SystemChat") {
                assertThat(ending.filter { STAR_WORD.containsMatchIn(it) })
                    .describedAs("star amounts in chat: ${quote(ending, 40)}").isEmpty()
                "ok"
            }
        }
    }
}
