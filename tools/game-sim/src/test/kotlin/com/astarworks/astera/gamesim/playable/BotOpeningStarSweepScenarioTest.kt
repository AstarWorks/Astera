package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Annihilation Preparation: every bot of a 3v3v3 match is paid its first star within [LIMIT_S] seconds of the match start.
 * The bots of a team split the base supply (dropped stars, the ground cache, paying blocks) by team slot instead of all
 * chasing the same star (bot-ai, BotBrain.baseSweepStars / baseCacheDest / hungry).
 */
class BotOpeningStarSweepScenarioTest {

    @Test
    fun `each bot of every team is paid a star in the opening`() {
        for (seed in listOf(4L, 10L)) {
            MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", teams = listOf("red", "blue", "yellow"), botsPerTeam = 3, seed = seed)).use { d ->
                var started: Long? = null
                val first = HashMap<String, Long>()
                var cursor = 0
                d.onTick { tick ->
                    if (started == null) started = d.sim.events.filterIsInstance<MatchStarted>().firstOrNull()?.atTick
                    val t0 = started ?: return@onTick
                    for (e in d.sim.events.subList(cursor, d.sim.events.size).toList()) if (e is StarsCollected) {
                        d.seats.firstOrNull { it.entity.id == e.playerId }?.let { first.putIfAbsent(it.name, (tick - t0) / 20) }
                    }
                    cursor = d.sim.events.size
                }
                d.step(((LIMIT_S + 30) * 20 + 2400).toInt())
                val late = d.seats.map { it.name }.filter { (first[it] ?: Long.MAX_VALUE) > LIMIT_S }
                println("[opening-stars] seed=$seed first=$first")
                assertThat(late).`as`("seed $seed: bots without a star by ${LIMIT_S}s").isEmpty()
            }
        }
    }

    private companion object {
        const val LIMIT_S = 60L
    }
}
