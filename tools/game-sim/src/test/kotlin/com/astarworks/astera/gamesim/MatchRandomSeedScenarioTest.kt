package com.astarworks.astera.gamesim

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeModifier
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.ISpawnSelector
import com.astarworks.astera.match.application.service.SpawnStrategy
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor
import com.astarworks.astera.match.domain.anomaly.AnomalyType
import com.astarworks.astera.match.domain.event.AnomalyTriggered
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.mode.GameModeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** plugin/match random streams (spawn selection, anomaly schedule) derive from the sim seed: same seed, same draws. */
class MatchRandomSeedScenarioTest {

    private fun sample(seed: Long): List<String> {
        val out = mutableListOf<String>()
        scenario("match-random-seed-$seed", seed = seed) {
            val players = listOf(player("p1"), player("p2"), player("p3"))
            val matchmaker = sim.services.getOrThrow<IMatchmaker>()
            val mode = MatchmakingMode.Custom(GameModeId("annihilation"))
            players.forEach { check(matchmaker.enqueue(it.id, mode) is Result.Success) }
            check(advanceUntil(600) { eventsOf<MatchStarted>().isNotEmpty() }) { "match did not start" }
            val started = eventsOf<MatchStarted>().single()
            val match = checkNotNull(sim.services.getOrThrow<IMatchRepository>().findById(started.matchId))
            val stage = checkNotNull(sim.services.getOrThrow<IStageRegistry>().find(started.stageId))
            val selector = sim.services.getOrThrow<ISpawnSelector>()
            repeat(40) {
                val pick = selector.selectFor(players[0].id, match.id, stage, SpawnStrategy.Random)
                out += "spawn ${pick?.position}"
            }
            val types = listOf(AnomalyType.ResourceSpike, AnomalyType.TimeWarp, AnomalyType.FogOfWar, AnomalyType.GravityShift)
            sim.services.getOrThrow<IAnomalyScheduleRepository>().setForMatch(
                match.id,
                types.mapIndexed { i, t ->
                    AnomalyDescriptor(t, weight = i + 1, durationTicks = 40L, attributeKey = AttributeKey("generator.period_multiplier"), modifierOperation = AttributeModifier.Operation.MULTIPLY, modifierValue = 0.5)
                },
            )
            advance(6000)
            eventsOf<AnomalyTriggered>().forEach { out += "anomaly ${it.type} @${it.atTick}" }
        }
        return out
    }

    @Test
    fun `same seed gives identical spawn picks and anomaly schedule, another seed differs`() {
        val a = sample(1111L)
        val b = sample(1111L)
        val c = sample(2222L)
        assertThat(a.count { it.startsWith("anomaly") }).`as`("anomalies fired").isPositive()
        assertThat(b).isEqualTo(a)
        assertThat(c).isNotEqualTo(a)
    }
}
