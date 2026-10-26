package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * G1: a whole Norma 2v2 match, played by four bots, ending on its own win condition (no admin end).
 *
 * queue -> match found -> countdown -> ACTIVE -> pistol duels (aim, shoot, reload) -> deaths, respawns and score on the
 * sidebar -> one player takes the KILL goal at every level and reaches the top Norma level (content is shortened by
 * docker/e2e-tune-content.sh: 3 levels, kill goals 1 then 2, so 3 kills) which ends the match; kills alone never do ->
 * summary, rewards, back in the lobby.
 *
 * The arena is a floating sky-island stage in a world slot of `world` (content/stages/example-arena.yaml, ADR-0255; coordinates are stage-local): red
 * island at x=-66, blue at x=+66 (radius 20), centre island (radius 22), north/south flank islands at z=-/+62; the top layer is y=64, bots stand at y=65.
 */
class NormaPlaythroughE2eTest : PlaythroughBase() {

    private val suite = "Norma playthrough"

    private fun side(b: Bot): String? = localPosOf(b)?.let { if (it.first < 0) "red" else "blue" }

    @Test
    fun `norma 2v2 is played to the top norma level and ends by itself`() {
        val logFrom = logLineCount()
        val bots = (1..4).map { bot("Nm$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }

        // --- queue -> ACTIVE ------------------------------------------------------------------------------------
        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join skirmish") }
        val acks = bots.map { b -> b.chatSince(chatFrom.getValue(b)) }
        E2eReport.check(suite, "all four bots are acknowledged by the queue", "SystemChat") {
            acks.forEachIndexed { i, a -> assertThat(a).describedAs("no reply for bot ${i + 1}").isNotEmpty() }
            assertThat(rawKeys(acks.flatten())).describedAs("raw i18n keys").isEmpty()
            quote(acks[0])
        }
        val countdown = awaitMatch("COUNTDOWN", 40_000)
        val active = awaitMatch("ACTIVE", 60_000)
        val secondsToActive = (System.nanoTime() - tQueue) / 1_000_000_000
        E2eReport.check(suite, "queue forms a match and it goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("match never ACTIVE; countdown seen=$countdown; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            assertThat(secondsToActive).describedAs("queue -> ACTIVE took ${secondsToActive}s").isLessThan(45)
            "queue -> ACTIVE in ${secondsToActive}s (e2e timers: 3s queue + 5s countdown); countdown phase observed=${countdown != null}; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(2_000)

        val teams = bots.associateWith { side(it) }
        val red = bots.filter { teams[it] == "red" }
        val blue = bots.filter { teams[it] == "blue" }
        E2eReport.check(suite, "teams are 2 vs 2 and each side spawns at its own end of the arena", "RCON data get Pos") {
            assertThat(red).describedAs("teams by spawn side: ${teams.mapKeys { it.key.name }}").hasSize(2)
            assertThat(blue).hasSize(2)
            "red=${red.map { it.name }} blue=${blue.map { it.name }} positions=${bots.map { posOf(it) }}"
        }
        E2eReport.check(suite, "every bot spawns standing on a solid island block inside its slot (not the lobby)", "RCON data get Dimension/Pos + execute unless block") {
            val problems = bots.mapNotNull { islandStanceProblem(it) }
            assertThat(problems).describedAs("bots not on an arena island: $problems").isEmpty()
            "slot ($originX, $originZ), solid block under every bot: ${bots.map { it.name to posOf(it) }}"
        }
        E2eReport.check(suite, "the arena is built: generator pedestals, team-coloured spawn pads, and void between the islands", "RCON execute if block") {
            val pedestals = listOf(Triple(0, 64, -62), Triple(0, 64, 62), Triple(12, 64, 0), Triple(-12, 64, 0))
            val missing = pedestals.filterNot { (x, y, z) -> arenaBlockIs(x, y, z, "minecraft:iron_block") }
            assertThat(missing).describedAs("generator pedestal (iron_block) missing at $missing").isEmpty()
            assertThat(arenaBlockIs(-70, 64, 0, "minecraft:red_terracotta")).describedAs("red spawn pad").isTrue()
            assertThat(arenaBlockIs(70, 64, 0, "minecraft:blue_terracotta")).describedAs("blue spawn pad").isTrue()
            assertThat(arenaSolid(10, 64, -40) || arenaSolid(10, 60, -40)).describedAs("blocks in the gap beside the north bridge (the arena is islands, not a plane)").isFalse()
            assertThat(arenaSolid(0, 64, -40)).describedAs("the bridge between the centre and the north outpost").isTrue()
            "4 generator pedestals, red/blue spawn pads, bridge at (0,64,-40), void at (10,64,-40)"
        }
        E2eReport.check(suite, "match sidebar shows the team scores at 0 and replaced the lobby sidebar", "SetObjective/SetScore") {
            val lines = sidebar(bots[0])
            assertThat(bots[0].sidebarTitle()).describedAs("still the lobby sidebar").isNotEqualTo(lobbyTitle)
            assertThat(lines.count { it.contains("Red", true) || it.contains("Blue", true) }).describedAs("no team score lines: $lines").isGreaterThanOrEqualTo(2)
            assertThat(rawKeys(lines)).isEmpty()
            quote(lines)
        }
        E2eReport.check(suite, "the sidebar names the win condition (reach Norma level 3) and every team's kills", "SetScore") {
            val lines = sidebar(bots[0])
            assertThat(lines.any { it.contains("level", true) && it.contains("3") && it.contains("win", true) && !it.contains("Red", true) && !it.contains("Blue", true) })
                .describedAs("no win-condition line (reach level 3): $lines").isTrue()
            assertThat(lines.filter { it.contains("Red", true) || it.contains("Blue", true) }.filter { it.contains("kills", true) })
                .describedAs("team lines without a kill count: $lines").hasSizeGreaterThanOrEqualTo(2)
            quote(lines)
        }
        E2eReport.check(suite, "the sidebar footer shows the player's own Norma level and current goal (never cut off)", "SetScore") {
            val lines = sidebar(bots[0])
            assertThat(lines.any { it.contains("Norma", true) && it.contains("Lv", true) }).describedAs("no Norma level line: $lines").isTrue()
            assertThat(lines.any { it.contains("Goal", true) && it.contains("/") }).describedAs("no current-goal line (Goal: Stars|Kills n/m): $lines").isTrue()
            quote(lines)
        }
        if (red.size != 2 || blue.size != 2) return

        // --- the fight ---------------------------------------------------------------------------------------------
        // red[0] makes 3 kills with the KILL goal (level 1 -> 2 after one, 2 -> 3 after two more = the top level, which wins), blue makes 2.
        // Everyone is re-armed (full magazine) before each duel.
        val script = listOf(red[0] to blue[0], blue[1] to red[1], red[0] to blue[1], blue[0] to red[1], red[0] to blue[0])
        val expectedRed = intArrayOf(1, 1, 2, 2, 3)
        val expectedBlue = intArrayOf(0, 1, 1, 2, 2)
        val respawnMs = mutableListOf<Long>()
        val duelNotes = mutableListOf<String>()
        script.forEachIndexed { i, (shooter, victim) ->
            val slot = equip(shooter, "pistol")
            // Each level-up re-opens the goal at STAR and the e2e generators pay every 3 s, so a bot loitering near one could
            // climb by stars. Everybody takes the KILL goal again before every duel, which keeps the climb on the kills.
            bots.forEach { it.command("astera norma choose kill") }
            val last = i == script.lastIndex
            val shooterDeaths = shooter.deaths.size
            val fight = duel(shooter, victim, keepAlive = bots.filter { it != shooter && it != victim })
            duelNotes += "kill ${i + 1}: ${shooter.name} -> ${victim.name}: ${fight.shots} shots, ${fight.reloads} reload(s), ${fight.millis} ms, killed=${fight.killed}, slot=$slot"
            E2eReport.check(suite, "kill ${i + 1}/${script.size}: ${shooter.name} kills ${victim.name} with pistol shots", "PlayerCombatKill") {
                assertThat(slot).describedAs("pistol never reached the hotbar").isNotNull()
                assertThat(fight.killed || (last && matchLines().isEmpty())).describedAs("no kill after ${fight.shots} shots (${fight.reloads} reloads). victim status='${status(victim)}' shooter status='${status(shooter)}' shooter pos=${posOf(shooter)} victim pos=${posOf(victim)}").isTrue()
                assertThat(shooter.deaths.size).describedAs("the shooter died while shooting").isEqualTo(shooterDeaths)
                "${duelNotes.last()}; victim death text=${victim.deaths.lastOrNull()}"
            }
            bots.forEach { it.command("astera norma choose kill") }
            if (!fight.killed) {
                // Fallback so the rest of the loop (win condition, ending) is still exercised.
                rcon("damage ${victim.name} 1000 minecraft:magic by ${shooter.name}")
                victim.waitUntil(5_000) { victim.deaths.isNotEmpty() }
            }
            if (!last) {
                Thread.sleep(1_500)
                E2eReport.check(suite, "kill ${i + 1}: the sidebar score follows (red ${expectedRed[i]} - blue ${expectedBlue[i]})", "SetScore") {
                    val r = scoreOf(shooter, "Red")
                    val b = scoreOf(shooter, "Blue")
                    assertThat(r to b).describedAs("sidebar ${sidebar(shooter)}").isEqualTo(expectedRed[i] to expectedBlue[i])
                    "sidebar: ${sidebar(shooter)}"
                }
            }
            if (last) return@forEachIndexed
            val ms = awaitRespawn(victim)
            if (ms != null) respawnMs += ms
            E2eReport.check(suite, "kill ${i + 1}: ${victim.name} respawns on its own and can play again", "RCON playerGameType") {
                assertThat(ms).describedAs("victim never left spectator (game type ${gameTypeOf(victim)}); chat=${victim.chatTexts().takeLast(3)}").isNotNull()
                assertThat(ms!!).describedAs("respawn took ${ms}ms (content respawn_delay_seconds is 2 in the e2e image)").isLessThan(12_000)
                "respawned ${ms} ms after the kill; pos=${posOf(victim)}"
            }
        }
        E2eReport.notes += "Norma playthrough: ${duelNotes.joinToString(" | ")}; respawn ms=$respawnMs"

        // --- the end: no admin, red[0]'s third kill (Norma level 3) must end the match -------------------------------------------
        val ended = awaitNoMatch(40_000, keepAlive = bots)
        E2eReport.check(suite, "reaching the top Norma level ends the match by itself (no admin end)", "RCON astera match list") {
            assertThat(ended).describedAs("match still listed 40s after the winning kill: ${matchLines()}; sidebar=${sidebar(red[0])}").isTrue()
            "match gone"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "winners are told they won (VICTORY chat/summary names the right outcome)", "SystemChat") {
            val winnerChat = red[0].chat.drop(chatFrom.getValue(red[0])).map { clean(it.text) }
            val loserChat = blue[0].chat.drop(chatFrom.getValue(blue[0])).map { clean(it.text) }
            val scoreLine = winnerChat.firstOrNull { it.startsWith("Score") }.orEmpty()
            assertThat(winnerChat.any { it.contains("Red") && it.contains("Norma level") }).describedAs("no 'Red reached Norma level' reason; result chat: ${quote(winnerChat, 40)}").isTrue()
            assertThat(Regex("Red\\D*3").containsMatchIn(scoreLine)).describedAs("score line should read Red 3: '$scoreLine'").isTrue()
            val reasons = (winnerChat + loserChat).filter { it.contains("Red") && it.contains("Norma level", true) }
            assertThat(winnerChat.none { it.contains("objective completion", true) }).describedAs("generic win reason: ${quote(winnerChat, 40)}").isTrue()
            assertThat(reasons).describedAs("no 'Red reached Norma level 3' reason in the result: ${quote(winnerChat, 40)}").isNotEmpty()
            "winner score line='$scoreLine'; loser score line='${loserChat.firstOrNull { it.startsWith("Score") }}'; reason='${reasons.firstOrNull()}'"
        }
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
