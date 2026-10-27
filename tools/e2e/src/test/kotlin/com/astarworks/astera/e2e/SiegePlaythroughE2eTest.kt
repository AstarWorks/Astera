package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * G1: a whole Break Core (siege) match with two bots: both cores intact on the sidebar, a team cannot break its own
 * core, breaking the enemy core eliminates that team and ends the match with the right winner, then summary, rewards
 * and the way back to the lobby. The stage is a sky-island arena in a world slot (ADR-0255, stage-local coordinates): cores stand at
 * x = -90 (red) and x = +90 (blue), y = 65 (one above the island top), z = 0 (content/stages/siege-isles.yaml), at the back of
 * the 37-radius team islands (centres x = -82 / +82) behind a wall arc; the front of each core stays open.
 */
class SiegePlaythroughE2eTest : PlaythroughBase() {

    private val suite = "Siege (break-core) playthrough"

    /** `N Stars` on the match sidebar (the Norma layer's footer), or null. */
    private fun starCount(b: Bot): Int? =
        sidebar(b).firstNotNullOfOrNull { Regex("(\\d+)\\s*Stars\\b", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** `Lv 2/3` on the match sidebar as (level, max), or null. */
    private fun levelOf(b: Bot): Pair<Int, Int>? =
        sidebar(b).firstNotNullOfOrNull { Regex("Lv\\s*(\\d+)/(\\d+)").find(it)?.let { m -> m.groupValues[1].toInt() to m.groupValues[2].toInt() } }

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

        val sideOf = bots.associateWith { b -> localPosOf(b)?.let { if (it.first < 0) "red" else "blue" } }
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

        E2eReport.check(suite, "both bots spawn standing on a solid island block inside its slot (not the lobby)", "RCON data get Dimension/Pos + execute unless block") {
            val problems = listOf(attacker, defender).mapNotNull { islandStanceProblem(it) }
            assertThat(problems).describedAs("bots not on an arena island: $problems").isEmpty()
            "slot ($originX, $originZ), solid block under both bots: ${listOf(attacker, defender).map { it.name to posOf(it) }}"
        }

        // The core blocks must really stand in the world, on a pedestal of the built island.
        E2eReport.check(suite, "both core blocks exist in the slot at match start, on built pedestals", "RCON execute if block") {
            val blueCore = arenaBlockIs(90, 65, 0, "minecraft:emerald_block")
            val redCore = arenaBlockIs(-90, 65, 0, "minecraft:emerald_block")
            assertThat(blueCore).describedAs("no emerald block at the blue core cell (90 65 0)").isTrue()
            assertThat(redCore).describedAs("no emerald block at the red core cell (-90 65 0)").isTrue()
            assertThat(arenaBlockIs(90, 64, 0, "minecraft:polished_blackstone_bricks")).describedAs("blue core pedestal").isTrue()
            assertThat(arenaBlockIs(-90, 64, 0, "minecraft:polished_blackstone_bricks")).describedAs("red core pedestal").isTrue()
            "emerald cores at (+-90, 65, 0) on blackstone-brick pedestals"
        }
        E2eReport.check(suite, "the objective anchors are built: jewelry and wool pedestals, TNT pedestal, void between the islands", "RCON execute if block") {
            assertThat(arenaBlockIs(73, 64, 10, "minecraft:chiseled_quartz_block")).describedAs("blue jewelry pedestal (73 64 10)").isTrue()
            assertThat(arenaBlockIs(-73, 64, 10, "minecraft:chiseled_quartz_block")).describedAs("red jewelry pedestal (-73 64 10)").isTrue()
            assertThat(arenaBlockIs(0, 64, -6, "minecraft:tnt") || arenaBlockIs(0, 64, -6, "minecraft:obsidian")).describedAs("TNT spawn pedestal (0 64 -6)").isTrue()
            listOf(Triple(0, 64, -62), Triple(0, 64, 6), Triple(0, 64, 62)).forEach { (x, y, z) ->
                assertThat(arenaSolid(x, y, z)).describedAs("wool control point floor ($x $y $z)").isTrue()
            }
            assertThat(arenaSolid(34, 64, 12) || arenaSolid(34, 50, 12)).describedAs("gap beside the bridge to the blue island at (34 64 12): the arena is islands, not a plane").isFalse()
            "jewelry pedestals, TNT pedestal, 3 wool points; void at (34, 64, 12)"
        }

        // --- the Norma layer is on in a siege mode too (MODE-15) ----------------------------------------------------
        // Stars, the Norma footer and the level-up are not a mode of their own: break-core has star generators on its
        // stage (content/generators/siege-*.yaml, e2e period 3 s) and the footer shows the level next to the cores.
        E2eReport.check(suite, "the break-core sidebar carries the Norma footer (stars, level) next to the core lines", "SetScore (sidebar)") {
            val lines = sidebar(attacker)
            assertThat(starCount(attacker)).describedAs("no stars line: $lines").isEqualTo(0)
            assertThat(levelOf(attacker)).describedAs("no Norma level line: $lines").isEqualTo(1 to 3)
            assertThat(lines.count { it.contains("intact", true) }).describedAs("core lines gone: $lines").isEqualTo(2)
            quote(lines)
        }
        // Stand on the east centre generator (12.5, 65, 0.5), stage-local until a pickup shows; leave at once after the level-up so a
        // second pop cannot carry the attacker to the top level (that would end the match before the core test).
        tpArena(attacker.name, 12.5, 65.0, 0.5, 0f, 0f)
        val gotStar = attacker.waitUntil(15_000) { (starCount(attacker) ?: 0) >= 1 }
        val leveledUp = attacker.waitUntil(6_000) { (levelOf(attacker)?.first ?: 1) >= 2 }
        tpArena(attacker.name, -79.5, 65.0, 0.5, 90f, 10f)
        E2eReport.check(suite, "a star pickup on a break-core generator shows on the sidebar", "SetScore (sidebar) / item pickup") {
            assertThat(gotStar).describedAs("no star pickup within 15s on the siege generator; sidebar=${sidebar(attacker)}").isTrue()
            "stars=${starCount(attacker)}"
        }
        E2eReport.check(suite, "carrying the star goal levels the player up to Lv 2 in break-core", "SetScore (sidebar) / chat") {
            assertThat(leveledUp).describedAs("no Norma level-up after the pickup; sidebar=${sidebar(attacker)} chat=${attacker.chatTexts().takeLast(5)}").isTrue()
            assertThat(matchLines()).describedAs("match ended by the level-up (top level is 3)").isNotEmpty()
            "level=${levelOf(attacker)}"
        }
        Thread.sleep(500)

        // --- a team cannot break its own core ------------------------------------------------------------------
        rcon("effect give ${attacker.name} minecraft:haste 600 100 true")
        rcon("item replace entity ${attacker.name} hotbar.7 with minecraft:diamond_pickaxe")
        attacker.selectHotbar(7)
        Thread.sleep(300)
        tpArena(attacker.name, -79.5, 65.0, 0.5, 90f, 10f) // open front of the red core, between its two posts
        Thread.sleep(500)
        attacker.dig(-90 + originX, 65, originZ)
        Thread.sleep(1_500)
        E2eReport.check(suite, "breaking your own core does nothing (core stays, team stays alive)", "SetScore (sidebar)") {
            val lines = sidebar(attacker)
            assertThat(lines.count { it.contains("intact", true) }).describedAs("own core fell? $lines").isEqualTo(2)
            assertThat(matchLines()).describedAs("match ended by breaking your own core").isNotEmpty()
            assertThat(arenaBlockIs(-90, 65, 0, "minecraft:emerald_block")).describedAs("own core block missing").isTrue()
            quote(lines)
        }

        // --- the void under the islands kills ------------------------------------------------------------------
        val defenderDeaths = defender.deaths.size
        // Protocol bots have no gravity, so put the bot straight below the kill plane (y=30), 29 blocks north of the north island: void.
        tpArena(defender.name, 0.5, 28.0, -94.5, 0f, 0f)
        val fell = defender.waitUntil(10_000) { defender.deaths.size > defenderDeaths }
        E2eReport.check(suite, "falling into the void below the islands kills the player (kill plane)", "death screen (void kill plane)") {
            assertThat(fell).describedAs("no death within 10s of being below the kill plane; pos=${posOf(defender)}").isTrue()
            "death screen after the void drop; text=${defender.deaths.lastOrNull()}"
        }
        val back = awaitRespawn(defender)
        E2eReport.check(suite, "the player who fell respawns on the island and can play again", "RCON data get Pos") {
            assertThat(back).describedAs("void victim never respawned").isNotNull()
            assertThat(islandStanceProblem(defender)).describedAs("respawned off an island").isNull()
            "respawned ${back} ms after the death; pos=${posOf(defender)}"
        }

        // --- break the enemy core -----------------------------------------------------------------------------------
        tpArena(attacker.name, 79.5, 65.0, 0.5, 270f, 10f) // open front of the blue core, between its two posts
        Thread.sleep(500)
        attacker.dig(90 + originX, 65, originZ)
        var fallen = attacker.waitUntil(4_000) { sidebar(attacker).any { it.contains("core down", true) } || matchLines().isEmpty() }
        var creativeFallback = false
        if (!fallen) {
            // Survival dig did not break it (tool speed / game mode): creative breaks instantly, so the rest of the loop still runs.
            creativeFallback = true
            rcon("gamemode creative ${attacker.name}")
            Thread.sleep(300)
            attacker.dig(90 + originX, 65, originZ)
            fallen = attacker.waitUntil(5_000) { sidebar(attacker).any { it.contains("core down", true) } || matchLines().isEmpty() }
        }
        E2eReport.check(suite, "breaking the enemy core eliminates that team", "SetScore (sidebar) / chat") {
            assertThat(fallen).describedAs("core never fell; deaths=${deathsSince(logFrom)} sidebar=${sidebar(attacker)} chat=${attacker.chatTexts().takeLast(5)}").isTrue()
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
