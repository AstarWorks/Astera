package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryNormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.core.domain.norma.Norma
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.norma.NormaTarget
import com.astarworks.astera.core.domain.norma.PeriodKind
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class MissionProgressOnKillListenerTest {

    private lateinit var normaService: InMemoryNormaService
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: MissionProgressOnKillListener
    private val killerId = PlayerId(UUID.randomUUID())
    private val victimId = PlayerId(UUID.randomUUID())
    private val normaId = NormaId("daily-killer-5")

    private val fiveKillsNorma = Norma(
        id = normaId,
        target = NormaTarget.Kills(5),
        reward = NormaReward(currencyId = "STAR", currencyAmount = 50, xp = 100),
        period = PeriodKind.Daily,
        displayKey = MessageKey("astera.norma.daily-killer-5.name"),
    )

    @BeforeEach
    fun setUp() {
        normaService = InMemoryNormaService(listOf(fiveKillsNorma))
        eventBus = FakeDomainEventBus()
        listener = MissionProgressOnKillListener(normaService, eventBus)
    }

    private fun makeKillEvent(killer: PlayerId? = killerId): PlayerKilled =
        PlayerKilled(victim = victimId, killer = killer, source = DamageSource.Environment.Generic, atTick = 1L)

    @Test
    fun `handle does nothing when killer is null`() {
        listener.handle(makeKillEvent(killer = null))
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `handle records kill progress without completing at 4 kills`() {
        repeat(4) { listener.handle(makeKillEvent()) }
        assertThat(eventBus.publishedOfType<MissionCompleted>()).isEmpty()
    }

    @Test
    fun `handle triggers MissionCompleted on 5th kill`() {
        repeat(5) { listener.handle(makeKillEvent()) }
        val completed = eventBus.publishedOfType<MissionCompleted>()
        assertThat(completed).hasSize(1)
        assertThat(completed.first().playerId).isEqualTo(killerId)
        assertThat(completed.first().normaId).isEqualTo(normaId)
        assertThat(completed.first().reward.currencyId).isEqualTo("STAR")
    }

    @Test
    fun `handle does not fire MissionCompleted twice after already completed`() {
        repeat(5) { listener.handle(makeKillEvent()) }
        // 6th kill should NOT trigger another MissionCompleted
        listener.handle(makeKillEvent())
        val completed = eventBus.publishedOfType<MissionCompleted>()
        assertThat(completed).hasSize(1)
    }
}
