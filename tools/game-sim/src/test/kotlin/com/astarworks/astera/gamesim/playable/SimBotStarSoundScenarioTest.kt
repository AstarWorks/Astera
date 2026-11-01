package com.astarworks.astera.gamesim.playable

import com.astarworks.astera.match.domain.event.StarsCollected
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * SimBotHost parity with the live bot client: a bot paid stars (every source publishes StarsCollected) hears the
 * `astera:star/pickup` cue in `sounds`, once per payout and cumulatively; a bot nobody paid hears nothing.
 */
class SimBotStarSoundScenarioTest {

    @Test
    fun `a paid bot hears one star pickup cue per payout`() {
        MatchDriver.boot(MatchConfig(mode = "annihilation", stage = "annihilation-arena", teams = listOf("red", "blue"), botsPerTeam = 2, seed = 4L)).use { d ->
            val paid = HashMap<String, Int>()
            var cursor = 0
            d.onTick {
                for (e in d.sim.events.subList(cursor, d.sim.events.size).toList()) if (e is StarsCollected && e.amount > 0) {
                    d.seats.firstOrNull { it.entity.id == e.playerId }?.let { paid.merge(it.name, 1, Int::plus) }
                }
                cursor = d.sim.events.size
            }
            assertThat(d.stepUntil(20 * 120) { paid.isNotEmpty() }).`as`("some bot is paid a star").isTrue()
            for (seat in d.seats) {
                val cues = d.hostOf(seat).sounds.count { it == "astera:star/pickup" }
                assertThat(cues).`as`("${seat.name} cues == payouts").isEqualTo(paid[seat.name] ?: 0)
            }
        }
    }
}
