package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.application.usecase.stats.RecordKillUseCase
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.stats.domain.event.XpGranted
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RewardOnKillListenerTest {

    private lateinit var repository: InMemoryPlayerStatsRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: RewardOnKillListener
    private val killerId = PlayerId(UUID.randomUUID())
    private val victimId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repository = InMemoryPlayerStatsRepository()
        eventBus = FakeDomainEventBus()
        val recordKill = RecordKillUseCase(repository, eventBus)
        val levelService = InMemoryLevelService()
        val grantXp = GrantXpUseCase(levelService, eventBus)
        listener = RewardOnKillListener(recordKill, grantXp)
    }

    private fun makeKillEvent(killer: PlayerId? = killerId): PlayerKilled =
        PlayerKilled(victim = victimId, killer = killer, source = DamageSource.Environment, atTick = 1L)

    @Test
    fun `handle increments killer stats`() {
        listener.handle(makeKillEvent())
        assertThat(repository.find(killerId)!!.kills).isEqualTo(1L)
    }

    @Test
    fun `handle publishes StatsUpdated Kill`() {
        listener.handle(makeKillEvent())
        val events = eventBus.publishedOfType<StatsUpdated>()
        assertThat(events).anyMatch { it.playerId == killerId && it.kind == StatsUpdated.Kind.Kill }
    }

    @Test
    fun `handle grants 100 xp to killer`() {
        listener.handle(makeKillEvent())
        val xpEvents = eventBus.publishedOfType<XpGranted>()
        assertThat(xpEvents).hasSize(1)
        assertThat(xpEvents.first().playerId).isEqualTo(killerId)
        assertThat(xpEvents.first().amount).isEqualTo(100L)
    }

    @Test
    fun `handle does nothing when killer is null`() {
        listener.handle(makeKillEvent(killer = null))
        assertThat(repository.find(killerId)).isNull()
        assertThat(eventBus.published).isEmpty()
    }
}
