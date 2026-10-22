package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * G1: a whole Break Core (siege) match with two bots: both cores intact on the sidebar, a team cannot break its own
 * core, breaking the enemy core eliminates that team and ends the match with the right winner, then summary, rewards
 * and the way back to the lobby. Cores stand at x = -46 (red) and x = +46 (blue), y = -60, z = 0
 * (content/stages/siege-flat.yaml).
 */
class SiegePlaythroughE2eTest : PlaythroughBase() {

    private val suite = "Siege (break-core) playthrough"

    @Test
    fun `break-core is played until one core falls`() {
        val logFrom = logLineCount()
        val bots = (1..2).map { bot("Sg$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }

        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join break-core") }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        E2eReport.check(suite, "queue forms a break-core match and it goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("never ACTIVE; list=${matchLines()}; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(System.nanoTime() - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)

        val sideOf = bots.associateWith { b -> posOf(b)?.let { if (it.first < 0) "red" else "blue" } }
        val attacker = bots.firstOrNull { sideOf[it] == "red" }
        val defender = bots.firstOrNull { sideOf[it] == "blue" }
        E2eReport.check(suite, "the two bots start on opposite sides", "RCON data get Pos") {
            assertThat(listOfNotNull(attacker, defender)).describedAs("sides: ${sideOf.mapKeys { it.key.name }}").hasSize(2)
            "red=${attacker?.name} blue=${defender?.name}"
        }
        if (attacker == null || defender == null) return

        E2eReport.check(suite, "sidebar shows both cores intact", "SetScore (sidebar)") {
            val lines = sidebar(attacker)
            assertThat(attacker.sidebarTitle()).isNotEqualTo(lobbyTitle)
            assertThat(lines.count { it.contains("intact", true) }).describedAs("sidebar: $lines").isEqualTo(2)
            quote(lines)
        }

        // The core block must really stand in the world.
        val coreBlock = clean(rcon("execute if block 46 -60 0 minecraft:emerald_block"))
        E2eReport.check(suite, "the enemy core block exists in the world at match start", "RCON execute if block") {
            assertThat(coreBlock).describedAs("no emerald block at the blue core cell: '$coreBlock'").startsWith("Test passed")
            coreBlock
        }

        // --- a team cannot break its own core ------------------------------------------------------------------
        rcon("effect give ${attacker.name} minecraft:haste 600 100 true")
        rcon("item replace entity ${attacker.name} hotbar.7 with minecraft:diamond_pickaxe")
        attacker.selectHotbar(7)
        Thread.sleep(300)
        rcon("tp ${attacker.name} -46.5 -60.0 -2.5 0 20")
        Thread.sleep(500)
        attacker.dig(-46, -60, 0)
        Thread.sleep(1_500)
        E2eReport.check(suite, "breaking your own core does nothing (core stays, team stays alive)", "SetScore (sidebar)") {
            val lines = sidebar(attacker)
            assertThat(lines.count { it.contains("intact", true) }).describedAs("own core fell? $lines").isEqualTo(2)
            assertThat(matchLines()).describedAs("match ended by breaking your own core").isNotEmpty()
            val block = clean(rcon("execute if block -46 -60 0 minecraft:emerald_block"))
            assertThat(block).describedAs("own core block missing: $block").startsWith("Test passed")
            quote(lines)
        }

        // --- break the enemy core -----------------------------------------------------------------------------------
        rcon("tp ${attacker.name} 46.5 -60.0 -2.5 0 20")
        Thread.sleep(500)
        attacker.dig(46, -60, 0)
        var fallen = attacker.waitUntil(4_000) { sidebar(attacker).any { it.contains("core down", true) } || matchLines().isEmpty() }
        var creativeFallback = false
        if (!fallen) {
            // Survival dig did not break it (tool speed / game mode): creative breaks instantly, so the rest of the loop still runs.
            creativeFallback = true
            rcon("gamemode creative ${attacker.name}")
            Thread.sleep(300)
            attacker.dig(46, -60, 0)
            fallen = attacker.waitUntil(5_000) { sidebar(attacker).any { it.contains("core down", true) } || matchLines().isEmpty() }
        }
        E2eReport.check(suite, "breaking the enemy core eliminates that team", "SetScore (sidebar) / chat") {
            assertThat(fallen).describedAs("core never fell; sidebar=${sidebar(attacker)} chat=${attacker.chatTexts().takeLast(5)}").isTrue()
            assertThat(creativeFallback).describedAs("survival dig with haste-100 + diamond pickaxe did nothing; only creative worked").isFalse()
            "creativeFallback=$creativeFallback; chat=${attacker.chatTexts().takeLast(3)}"
        }

        val ended = awaitNoMatch(40_000, keepAlive = bots)
        E2eReport.check(suite, "the last intact core ends the match by itself (no admin end)", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed: ${matchLines()}").isTrue()
            "match gone"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
