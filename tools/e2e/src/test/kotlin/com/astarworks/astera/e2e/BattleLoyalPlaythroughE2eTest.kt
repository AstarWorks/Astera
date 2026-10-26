package com.astarworks.astera.e2e

import com.astarworks.astera.bots.BotBrain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Battle Loyal (team deathmatch, score_threshold 5, Norma time limit 300 s in the e2e image): two AI bots (BotBrain: A* navigation
 * to the enemy, pistol / sword) fight until a kill scores a team point ("scores!" in chat, kills on the sidebar) and the match ends
 * by itself with a winner (kill goal, Norma level, or the time-limit tie-break).
 */
class BattleLoyalPlaythroughE2eTest : ObjectivePlaythroughBase() {

    private val suite = "Battle Loyal playthrough"
    private val brains = mutableListOf<BotBrain>()

    @AfterEach
    fun stopBrains() {
        brains.forEach { it.close() }
        brains.clear()
    }

    @Test
    fun `battle-loyal scores a kill and ends with a winner`() {
        val ctx = startMatch(suite, "battle-loyal", "Bl") ?: return
        val bots = ctx.bots
        bots.forEach { b ->
            if (!b.waitUntil(3_000) { b.hotbar().take(8).any { it != -1 } }) {
                rcon("astera items give ${b.name} knight-sword")
                rcon("astera items give ${b.name} pistol")
                b.waitUntil(4_000) { b.hotbar().take(8).any { it != -1 } }
            }
        }
        E2eReport.check(suite, "both bots enter the match armed", "SetContainerContent") {
            bots.forEach { assertThat(it.hotbar().take(8).any { id -> id != -1 }).describedAs("${it.name} has no weapon: ${it.hotbar()}").isTrue() }
            "hotbars=${bots.map { it.hotbar() }}"
        }
        // chooseNormaGoal = false: this loop sends `norma choose kill` itself, so stars cannot end the match before a fight.
        bots.forEach { brains += BotBrain(it, objectives = ctx.objectives, chooseNormaGoal = false).start() }
        brains.forEach { it.mode = BotBrain.Mode.FIGHT }

        val deathsBefore = bots.sumOf { it.deaths.size }
        val tPlay = System.currentTimeMillis()
        var lastChooseAt = 0L
        var firstScoreMs = -1L
        while (System.currentTimeMillis() - tPlay < 330_000 && matchLines().isNotEmpty()) {
            if (System.currentTimeMillis() - lastChooseAt > 2_000) {
                lastChooseAt = System.currentTimeMillis()
                bots.forEach { it.command("astera norma choose kill") }
            }
            if (firstScoreMs < 0 && ctx.chatCount("scores!", bots[0]) >= 1) firstScoreMs = System.currentTimeMillis() - tPlay
            Thread.sleep(1_000)
        }
        val ended = matchLines().isEmpty()
        brains.forEach { it.mode = BotBrain.Mode.IDLE }
        val kills = bots.sumOf { it.deaths.size } - deathsBefore
        val summary = brains.mapIndexed { i, b -> "${bots[i].name}: ${b.summary()}" }
        E2eReport.notes += "Battle Loyal: first score after ${firstScoreMs}ms, deaths $kills, ${summary.joinToString(" | ")}; trace0=${brains.firstOrNull()?.traceText()}"

        E2eReport.check(suite, "a kill scores a team point (objective event: 'scores!' in chat)", "SystemChat / sidebar") {
            assertThat(kills).describedAs("nobody died in ${(System.currentTimeMillis() - tPlay) / 1000}s. $summary; positions=${bots.map { posOf(it) }}").isGreaterThanOrEqualTo(1)
            assertThat(ctx.chatCount("scores!", bots[0])).describedAs("no score message; chat=${ctx.chat(bots[0]).takeLast(8)}").isGreaterThanOrEqualTo(1)
            "scores=${ctx.chat(bots[0]).filter { it.contains("scores!") }}"
        }
        E2eReport.check(suite, "the match ends by itself (kill goal, Norma level or the time limit)", "RCON astera match list") {
            assertThat(ended).describedAs("match still running: ${matchLines()}; sidebar=${sidebar(bots[0])}").isTrue()
            "match gone after ${(System.currentTimeMillis() - tPlay) / 1000}s"
        }
        finishMatch(ctx, endTimeoutMs = 20_000)
    }
}
