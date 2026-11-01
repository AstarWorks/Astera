package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.combat.domain.event.PlayerKilled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The real BotBrain plays a whole Annihilation match headless (ADR-0316): 3 teams x 3 bots on the generated
 * annihilation-arena, driven by [MatchDriver] over real wiring and real content. The match runs to its end or the tick cap;
 * Not asserted: same-seed reproducibility. Remaining plugin-side sources, each needing a seeded stream from the sim
 * (request to the plugin/match owner): StartMatchUseCase.kt:74 draws the MatchId from UUID.randomUUID() and
 * BotLoadoutService.kt:152 seeds the bot weapon pairs from that id hash (pool `seed` unset), so loadouts differ from tick 0;
 * StarDropService.kt:61 and StarWaveService.kt:46 default to Random.Default (MatchStarWiring.kt:99/143 pass no seeded stream).
 * The per-bot maxSearchUs in BotBrain.summary() is wall-clock A* time and must be excluded from any equality compare.
 * The per-bot brain summaries and the end reason are printed so the bot AI can be tuned against numbers, not a live server.
 */
class BotBrainSimParityScenarioTest {

    private class Outcome(val summaries: List<String>, val endReason: String, val ticks: Long, val wallMs: Long)

    private fun play(seed: Long): Outcome {
        val started = System.nanoTime()
        MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", teams = listOf("red", "blue", "yellow"), botsPerTeam = 3, seed = seed)).use { driver ->
            driver.stepUntil(CAP) { driver.ended }
            val end = driver.endEvent()
            val reason = end?.let { "MatchEnded(reason=${it.reason}, winner=${it.winningTeam?.value}, atTick=${it.atTick})" } ?: "no end within $CAP ticks"
            val kills = driver.sim.eventsOf<PlayerKilled>().size
            val respawns = driver.sim.eventsOf<PlayerRespawned>().size
            val summaries = driver.seats.map { "${it.name} ${it.brain!!.summary()}" }
            println("[sim-parity seed=$seed] $reason tick=${driver.tick} kills=$kills respawns=$respawns brainErrors=${driver.brainErrors.size}")
            summaries.forEach { println("[sim-parity seed=$seed] $it") }
            driver.brainErrors.take(5).forEach { println("[sim-parity seed=$seed] brain error: $it") }
            assertThat(driver.brainErrors).`as`("brains never throw").isEmpty()
            return Outcome(summaries, reason, driver.tick, (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun count(summary: String, key: String): Int = Regex("$key=(\\d+)").find(summary)?.groupValues?.get(1)?.toInt() ?: 0

    @Test
    fun `a 3x3 annihilation match plays out with real fights, stars and bounded replans`() {
        val first = play(seed = 20261101L)
        println("[sim-parity] wall=${first.wallMs}ms for ${first.ticks} ticks")
        first.summaries.forEach { s ->
            assertThat(count(s, "stuckReplans")).`as`("bounded stuck replans: $s").isLessThan(MAX_STUCK_REPLANS)
        }
        assertThat(first.summaries.sumOf { count(it, "swings") + count(it, "shots") }).`as`("the bots attacked").isPositive()
        assertThat(first.summaries.sumOf { count(it, "stars") }).`as`("the bots collected stars").isPositive()
    }

    private companion object {
        const val CAP = 24_000
        const val MAX_STUCK_REPLANS = 200
    }
}
