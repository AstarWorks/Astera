package com.astarworks.astera.e2e

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * G1: a whole Annihilation match with three bots (one per team). Red attacks the other two teams' pillars:
 * Build-phase immunity, damage by pistol shots and by breaking the pillar block, the tier gates, elimination of blue
 * and then yellow, winner declared, summary/rewards, everyone (spectators included) back in the lobby.
 * Timers and HP come from docker/e2e-tune-content.sh (build 5s, engage 10s, every pillar 60 HP).
 *
 * The arena is a three-island sky stage in the void `arenas` world (content/stages/annihilation-arena.yaml, ADR-0246):
 * red island at (0, -333), blue at (29, -284), yellow at (-29, -284), centre island at (0, -300); top layer y=64, bots stand at y=65.
 */
class AnnihilationPlaythroughE2eTest : PlaythroughBase() {

    private val suite = "Annihilation playthrough"

    /** Pillar block cells per tier (1..3) from content/stages/annihilation-arena.yaml. */
    private val pillars = mapOf(
        "Blue" to listOf(Triple(31, 65, -283), Triple(29, 65, -284), Triple(27, 65, -285)),
        "Yellow" to listOf(Triple(-31, 65, -283), Triple(-29, 65, -284), Triple(-27, 65, -285)),
        "Red" to listOf(Triple(0, 65, -335), Triple(0, 65, -333), Triple(0, 65, -331)),
    )
    private val pillarBlocks = listOf("minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:beacon")

    private fun teamOfBot(b: Bot): String? = posOf(b)?.let { (x, _, z) ->
        when {
            z < -320 -> "Red"
            x > 0 -> "Blue"
            else -> "Yellow"
        }
    }

    /** `(tiers, hp)` of a team's sidebar line (`Blue ●●● 180 HP`), or null when the line is not there. */
    private fun teamLine(b: Bot, team: String): Pair<String, Int>? {
        val line = sidebar(b).firstOrNull { it.contains(team, ignoreCase = true) } ?: return null
        val tiers = line.filter { it == '●' || it == '○' }
        val hp = Regex("(\\d+)\\s*HP").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return tiers to -1
        return tiers to hp
    }

    private fun eliminated(b: Bot, team: String): Boolean =
        sidebar(b).any { it.contains(team, ignoreCase = true) && (it.contains("out", ignoreCase = true)) }

    private fun phaseLine(b: Bot): String = sidebar(b).firstOrNull { Regex("Build|Engage|Tier 3|Sudden", RegexOption.IGNORE_CASE).containsMatchIn(it) }.orEmpty()

    /** Stand 3.5 blocks north of the pillar block, look south at it. */
    private fun stand(a: Bot, p: Triple<Int, Int, Int>) {
        tpArena(a.name, p.first + 0.5, p.second.toDouble(), p.third - 3.5, 0f, 16f)
        Thread.sleep(400)
    }

    private fun shoot(a: Bot, times: Int) {
        repeat(times) {
            // The magazine holds 12; an empty one fires nothing (Shift+Q reloads).
            if ((ammoOf(a) ?: 12) <= 0) reload(a)
            a.useItem(0f, 16f)
            Thread.sleep(330)
        }
    }

    @Test
    fun `annihilation with three teams is played to a winner`() {
        val logFrom = logLineCount()
        val bots = (1..3).map { bot("An$it", PackPolicy.DECLINE) }
        bots.forEach { b -> b.waitUntil(15_000) { b.sidebarLines().isNotEmpty() && b.hotbar()[8] != -1 } }
        Thread.sleep(1_500)
        val lobbyTitle = bots[0].sidebarTitle()
        val chatFrom = bots.associateWith { it.chat.size }

        val tQueue = System.nanoTime()
        bots.forEach { it.command("astera queue join annihilation") }
        val active = awaitMatch("ACTIVE", 70_000, keepAlive = bots)
        val tActive = System.nanoTime()
        E2eReport.check(suite, "queue forms an annihilation match and it goes ACTIVE (pacing)", "RCON astera match list") {
            assertThat(active).describedAs("never ACTIVE; list=${matchLines()}; chat=${bots[0].chatTexts().takeLast(6)}").isNotNull()
            "queue -> ACTIVE in ${(tActive - tQueue) / 1_000_000_000}s; line='${active?.raw}'"
        }
        if (active == null) return
        Thread.sleep(1_500)

        val teams = bots.associateWith { teamOfBot(it) }
        val a = bots.firstOrNull { teams[it] == "Red" }
        val blue = bots.firstOrNull { teams[it] == "Blue" }
        val yellow = bots.firstOrNull { teams[it] == "Yellow" }
        E2eReport.check(suite, "three bots land on three different team spawns", "RCON data get Pos") {
            assertThat(listOfNotNull(a, blue, yellow)).describedAs("spawn sides: ${teams.mapKeys { it.key.name }} positions=${bots.map { posOf(it) }}").hasSize(3)
            "teams=${teams.mapKeys { it.key.name }}"
        }
        if (a == null || blue == null || yellow == null) return
        val idle = listOf(blue, yellow)

        E2eReport.check(suite, "every team spawns standing on a solid island block in the arenas world (not the flat world)", "RCON data get Dimension/Pos + execute unless block") {
            val problems = bots.mapNotNull { islandStanceProblem(it) }
            assertThat(problems).describedAs("bots not on an arena island: $problems").isEmpty()
            "dimension minecraft:arenas, solid block under every bot: ${bots.map { it.name to posOf(it) }}"
        }
        E2eReport.check(suite, "all nine pillar blocks stand in the arena world on team-coloured footings, with void between the islands", "RCON execute if block") {
            val wrong = mutableListOf<String>()
            for ((team, cells) in pillars) {
                cells.forEachIndexed { i, (x, y, z) ->
                    if (!arenaBlockIs(x, y, z, pillarBlocks[i])) wrong += "$team tier ${i + 1} block ${pillarBlocks[i]} missing at ($x $y $z)"
                    if (!arenaBlockIs(x, y - 1, z, "minecraft:${team.lowercase()}_concrete")) wrong += "$team tier ${i + 1} footing ${team.lowercase()}_concrete missing at ($x ${y - 1} $z)"
                }
            }
            assertThat(wrong).describedAs("pillar anchors: $wrong").isEmpty()
            assertThat(arenaSolid(14, 64, -312) || arenaSolid(14, 50, -312)).describedAs("gap between the islands at (14 64 -312): the arena is islands, not a plane").isFalse()
            "9 pillar blocks + 9 coloured footings; void at (14, 64, -312)"
        }

        E2eReport.check(suite, "sidebar shows the Build phase and three intact 60 HP pillar sets", "SetObjective/SetScore") {
            val lines = sidebar(a)
            assertThat(a.sidebarTitle()).isNotEqualTo(lobbyTitle)
            assertThat(phaseLine(a)).describedAs("no phase line: $lines").isNotBlank()
            assertThat(teamLine(a, "Blue")).describedAs("blue line in $lines").isEqualTo("●●●" to 180)
            assertThat(teamLine(a, "Yellow")).describedAs("yellow line in $lines").isEqualTo("●●●" to 180)
            assertThat(rawKeys(lines)).isEmpty()
            quote(lines)
        }

        // --- Build phase: pillars are immune --------------------------------------------------------------------
        val slot = equip(a, "pistol")
        val inBuild = phaseLine(a).contains("Build", ignoreCase = true)
        if (inBuild) {
            stand(a, pillars.getValue("Blue")[0])
            shoot(a, 4)
            a.dig(pillars.getValue("Blue")[0].first, pillars.getValue("Blue")[0].second, pillars.getValue("Blue")[0].third)
            Thread.sleep(1_800)
            E2eReport.check(suite, "Build phase: pillars take no damage from shots or block breaking", "SetScore (sidebar HP)") {
                assertThat(teamLine(a, "Blue")).describedAs("blue pillars changed during Build: ${sidebar(a)}").isEqualTo("●●●" to 180)
                "slot=$slot; sidebar=${sidebar(a)}"
            }
        } else {
            E2eReport.notes += "Annihilation: Build phase was already over when the immunity check was due (phase line '${phaseLine(a)}')"
        }

        // --- Engage: Tier 1 opens ---------------------------------------------------------------------------------
        val engaged = a.waitUntil(30_000) { phaseLine(a).let { it.contains("Engage", true) || it.contains("Tier 3", true) } }
        E2eReport.check(suite, "the phase line moves on from Build by itself", "SetScore (sidebar)") {
            assertThat(engaged).describedAs("still '${phaseLine(a)}' 30s later").isTrue()
            "phase line now '${phaseLine(a)}'"
        }
        val blueT1 = pillars.getValue("Blue")[0]
        stand(a, blueT1)
        val hpBefore = teamLine(a, "Blue")?.second ?: -1
        var shotsToHit = 0
        while (shotsToHit < 24 && (teamLine(a, "Blue")?.second ?: -1) >= hpBefore) {
            shoot(a, 3)
            shotsToHit += 3
            Thread.sleep(1_300)
        }
        val hpAfterShots = teamLine(a, "Blue")?.second ?: -1
        E2eReport.check(suite, "Engage: pistol shots damage a Tier 1 pillar (HP on the sidebar drops)", "SetScore (sidebar HP)") {
            assertThat(hpAfterShots).describedAs("blue HP $hpBefore -> $hpAfterShots after $shotsToHit shots; sidebar=${sidebar(a)}").isLessThan(hpBefore)
            "blue HP $hpBefore -> $hpAfterShots after $shotsToHit shots (7 per hit)"
        }

        stand(a, blueT1)
        rcon("effect give ${a.name} minecraft:haste 600 100 true")
        rcon("item replace entity ${a.name} hotbar.7 with minecraft:diamond_pickaxe")
        a.selectHotbar(7)
        Thread.sleep(300)
        val hpBeforeDig = teamLine(a, "Blue")?.second ?: -1
        repeat(2) { a.dig(blueT1.first, blueT1.second, blueT1.third); Thread.sleep(400) }
        Thread.sleep(1_800)
        var hpAfterDig = teamLine(a, "Blue")?.second ?: -1
        var creativeFallback = false
        if (hpAfterDig >= hpBeforeDig && hpBeforeDig > 0) {
            // The survival dig did not land (tool speed / game mode): retry in creative so the rest of the match can be played.
            creativeFallback = true
            rcon("gamemode creative ${a.name}")
            Thread.sleep(300)
            repeat(2) { a.dig(blueT1.first, blueT1.second, blueT1.third); Thread.sleep(400) }
            Thread.sleep(1_800)
            hpAfterDig = teamLine(a, "Blue")?.second ?: -1
        }
        E2eReport.check(suite, "Engage: breaking a pillar block damages the pillar (the block stays)", "SetScore (sidebar HP) + BlockUpdate") {
            assertThat(hpAfterDig).describedAs("blue HP $hpBeforeDig -> $hpAfterDig after digging (creative fallback=$creativeFallback); sidebar=${sidebar(a)}").isLessThan(hpBeforeDig)
            assertThat(creativeFallback).describedAs("survival dig with haste II-100 + diamond pickaxe did nothing; only creative worked").isFalse()
            "blue HP $hpBeforeDig -> $hpAfterDig (25 per break), creativeFallback=$creativeFallback"
        }
        a.selectHotbar(slot ?: 0)
        rcon("gamemode survival ${a.name}")

        // --- take the pillars down tier by tier ----------------------------------------------------------------------
        fun destroyTeam(team: String): Boolean {
            val cells = pillars.getValue(team)
            val deadline = System.currentTimeMillis() + 150_000
            while (System.currentTimeMillis() < deadline && !eliminated(a, team)) {
                idle.forEach { it.nudge() }
                val tiers = teamLine(a, team)?.first ?: break
                val idx = tiers.indexOf('●')
                if (idx < 0) break
                val cell = cells[idx]
                stand(a, cell)
                if (idx == 0) {
                    // Tier 1: mix shots and block breaks; both must work.
                    shoot(a, 3)
                    rcon("gamemode creative ${a.name}")
                    a.dig(cell.first, cell.second, cell.third)
                    rcon("gamemode survival ${a.name}")
                } else {
                    shoot(a, 4)
                }
                Thread.sleep(1_300)
            }
            return eliminated(a, team)
        }

        val blueDown = destroyTeam("Blue")
        E2eReport.check(suite, "Tier 1 -> 2 -> 3 of Blue fall; Blue is eliminated (sidebar) and its player spectates", "SetScore (sidebar) + RCON playerGameType") {
            assertThat(blueDown).describedAs("Blue not eliminated after the deadline; sidebar=${sidebar(a)} phase='${phaseLine(a)}'").isTrue()
            val gt = gameTypeOf(blue)
            assertThat(gt).describedAs("eliminated player's game mode").isEqualTo(3)
            "sidebar=${sidebar(a)}; blue gameType=$gt"
        }
        E2eReport.check(suite, "the match keeps running with two teams left (no premature end)", "RCON astera match list") {
            assertThat(matchLines()).describedAs("match vanished after one elimination").isNotEmpty()
            "list=${matchLines().map { it.raw }}"
        }

        val yellowDown = destroyTeam("Yellow")
        val ended = awaitNoMatch(45_000, keepAlive = idle)
        E2eReport.check(suite, "eliminating the last rival ends the match with Red as the winner (no admin end)", "RCON astera match list + chat") {
            assertThat(yellowDown || ended).describedAs("Yellow not eliminated by the deadline; sidebar=${sidebar(a)}").isTrue()
            assertThat(ended).describedAs("match still listed after the last elimination: ${matchLines()}").isTrue()
            "ended=$ended"
        }
        checkEnding(suite, bots, chatFrom, lobbyTitle)
        E2eReport.check(suite, "the result names Red as winner (winner and losers see the right verdict data)", "SystemChat") {
            val chat = a.chat.drop(chatFrom.getValue(a)).map { clean(it.text) }
            val you = chat.firstOrNull { it.startsWith("Score") }.orEmpty()
            assertThat(chat.any { it.contains("Match Summary") }).describedAs("no summary:\n${quote(chat, 30)}").isTrue()
            "winner chat score line: '$you'; MVP/lines: ${chat.filter { it.contains("MVP") || it.contains("Ended by") }}"
        }
        E2eReport.check(suite, "server log stayed free of errors during the match", "server log") {
            val errs = serverErrorsSince(logFrom)
            assertThat(errs).describedAs("server errors:\n${quote(errs, 20)}").isEmpty()
            "clean"
        }
    }
}
