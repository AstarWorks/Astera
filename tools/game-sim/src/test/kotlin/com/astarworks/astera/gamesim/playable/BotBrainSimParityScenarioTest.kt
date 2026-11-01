package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.domain.event.PlayerRespawned
import com.astarworks.astera.combat.domain.event.PlayerKilled
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The real BotBrain plays a whole Annihilation match headless (ADR-0316): 3 teams x 3 bots on the generated
 * annihilation-arena, driven by [MatchDriver] over real wiring and real content. The match runs to its end or the tick cap.
 * Reproducibility: the MatchId, bot loadouts and star services all draw from the sim seed, so two runs of the same seed
 * must produce an identical outcome (end reason, tick, kill/respawn counts, every per-bot brain summary); another seed must
 * differ. Only the wall-clock A* timing (`maxSearchUs`) is stripped from the compare. Anomaly ids are unseeded tags and are
 * not part of the compared outcome. The brain summaries and end reason are printed so the bot AI can be tuned against numbers.
 */
class BotBrainSimParityScenarioTest {

    private class Outcome(val summaries: List<String>, val endReason: String, val ticks: Long, val kills: Int, val respawns: Int, val wallMs: Long) {
        /** Everything deterministic: wall-clock timing stripped. */
        val fingerprint: List<String> = listOf("$endReason tick=$ticks kills=$kills respawns=$respawns") +
            summaries.map { it.replace(Regex("maxSearchUs=\\d+"), "maxSearchUs=*") }
    }

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
            return Outcome(summaries, reason, driver.tick, kills, respawns, (System.nanoTime() - started) / 1_000_000)
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

    @Test
    fun `same seed replays the same match, another seed plays a different one`() {
        val a = play(seed = SEED)
        val b = play(seed = SEED)
        assertThat(b.fingerprint).`as`("same seed, same outcome").isEqualTo(a.fingerprint)
        val c = play(seed = SEED + 1)
        assertThat(c.fingerprint).`as`("another seed differs").isNotEqualTo(a.fingerprint)
    }

    private companion object {
        const val SEED = 7L
        const val CAP = 24_000
        const val MAX_STUCK_REPLANS = 200
    }
}
