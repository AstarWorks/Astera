package com.astarworks.astera.core.application.service.match

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.world.InMemoryWorldRulesGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldTimeGateway
import com.astarworks.astera.core.application.service.world.InMemoryWorldWeatherGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchConfigApplied
import com.astarworks.astera.core.domain.match.MatchConfig
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldDifficulty
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryMatchConfigServiceTest {

    private val matchId = MatchId("test-match")
    private val world = WorldId("arena")

    private fun newSvc(tickClock: () -> Long = { 100L }): Triple<
        InMemoryMatchConfigService,
        InMemoryWorldRulesGateway,
        InMemoryDomainEventBus,
        > {
        val rules = InMemoryWorldRulesGateway()
        val time = InMemoryWorldTimeGateway()
        val weather = InMemoryWorldWeatherGateway()
        val bus = InMemoryDomainEventBus()
        return Triple(
            InMemoryMatchConfigService(rules, time, weather, bus, tickClock),
            rules,
            bus,
        )
    }

    @Test
    fun `configOf returns DEFAULT for unknown match`() {
        val (svc, _, _) = newSvc()
        assertThat(svc.configOf(matchId)).isEqualTo(MatchConfig.DEFAULT)
    }

    @Test
    fun `apply pushes config and updates rules gateway`() {
        val (svc, rules, _) = newSvc()
        val cfg = MatchConfig(
            pvpEnabled = false,
            difficulty = WorldDifficulty.HARD,
            keepInventoryOnDeath = true,
        )

        val result = svc.apply(matchId, world, cfg)
        assertThat(result).isInstanceOf(Result.Success::class.java)

        assertThat(rules.isPvP(world)).isFalse()
        assertThat(rules.getDifficulty(world)).isEqualTo(WorldDifficulty.HARD)
        assertThat(rules.isKeepInventoryOnDeath(world)).isTrue()
        assertThat(svc.configOf(matchId)).isEqualTo(cfg)
    }

    @Test
    fun `apply locks time when timeMode is non-null`() {
        val (svc, rules, _) = newSvc()
        val cfg = MatchConfig(timeMode = 6000L)

        svc.apply(matchId, world, cfg)
        assertThat(rules.isDoDaylightCycle(world)).isFalse()
    }

    @Test
    fun `apply locks weather when weatherMode is non-null`() {
        val (svc, rules, _) = newSvc()
        val cfg = MatchConfig(weatherMode = WeatherState.Rain(durationTicks = 6000))

        svc.apply(matchId, world, cfg)
        assertThat(rules.isDoWeatherCycle(world)).isFalse()
    }

    @Test
    fun `apply publishes MatchConfigApplied event`() {
        val (svc, _, bus) = newSvc { 42L }
        val captured = mutableListOf<MatchConfigApplied>()
        bus.subscribe(MatchConfigApplied::class) { captured += it }

        val cfg = MatchConfig(pvpEnabled = false)
        svc.apply(matchId, world, cfg)

        assertThat(captured).hasSize(1)
        assertThat(captured.first().matchId).isEqualTo(matchId)
        assertThat(captured.first().worldId).isEqualTo(world)
        assertThat(captured.first().config).isEqualTo(cfg)
        assertThat(captured.first().atTick).isEqualTo(42L)
    }

    @Test
    fun `resetToDefault restores vanilla rules and lifts overlay`() {
        val (svc, rules, _) = newSvc()
        val cfg = MatchConfig(pvpEnabled = false, keepInventoryOnDeath = true, timeMode = 0L)
        svc.apply(matchId, world, cfg)

        val previous = svc.resetToDefault(matchId)
        assertThat(previous).isEqualTo(cfg)
        assertThat(rules.isPvP(world)).isTrue()
        assertThat(rules.isKeepInventoryOnDeath(world)).isFalse()
        assertThat(rules.isDoDaylightCycle(world)).isTrue()
        assertThat(svc.configOf(matchId)).isEqualTo(MatchConfig.DEFAULT)
    }

    @Test
    fun `resetToDefault on unknown match is no-op`() {
        val (svc, _, _) = newSvc()
        assertThat(svc.resetToDefault(matchId)).isEqualTo(MatchConfig.DEFAULT)
    }

    @Test
    fun `MatchConfig damageMultiplier must be non-negative`() {
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MatchConfig(damageMultiplier = -0.5)
        }
        assertThat(ex.message).contains("damageMultiplier")
    }

    @Test
    fun `MatchConfig timeMode must be in 0_23999`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MatchConfig(timeMode = 30000L)
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            MatchConfig(timeMode = -1L)
        }
    }
}

// Helper Triple<A, B, C> to avoid the generic type explosion in the helper above.
private typealias Triple<A, B, C> = kotlin.Triple<A, B, C>
