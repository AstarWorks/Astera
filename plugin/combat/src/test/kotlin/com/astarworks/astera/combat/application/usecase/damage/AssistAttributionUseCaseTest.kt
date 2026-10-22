package com.astarworks.astera.combat.application.usecase.damage

import com.astarworks.astera.combat.application.service.InMemoryDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.damage.DamageHistoryWindow
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.AssistCredited
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.combat.CombatContext
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AssistAttributionUseCaseTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val killer = PlayerId(UUID.randomUUID())
    private val assistA = PlayerId(UUID.randomUUID())
    private val assistB = PlayerId(UUID.randomUUID())

    private fun kill(atTick: Long = 100L) = PlayerKilled(
        victim = victim,
        killer = killer,
        source = DamageSource.Environment.Generic,
        atTick = atTick,
    )

    @Test
    fun `a kill outside a match credits no assists (ADR-0237)`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(victim, DamageEntry(assistA, 5.0, at = 50L))
        val bus = FakeDomainEventBus()
        AssistAttributionUseCase(svc, bus).handle(kill(atTick = 100L).copy(context = CombatContext.Lobby))
        assertThat(bus.publishedOfType<AssistCredited>()).isEmpty()
    }

    @Test
    fun `single recent attacker yields one AssistCredited`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(victim, DamageEntry(assistA, 5.0, at = 50L))
        val bus = FakeDomainEventBus()
        AssistAttributionUseCase(svc, bus).handle(kill(atTick = 100L))
        val events = bus.publishedOfType<AssistCredited>()
        assertThat(events).hasSize(1)
        assertThat(events.single().player).isEqualTo(assistA)
        assertThat(events.single().victim).isEqualTo(victim)
        assertThat(events.single().atTick).isEqualTo(100L)
    }

    @Test
    fun `multiple distinct recent attackers each get one AssistCredited`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(victim, DamageEntry(assistA, 5.0, at = 40L))
        svc.record(victim, DamageEntry(assistB, 3.0, at = 70L))
        svc.record(victim, DamageEntry(assistA, 2.0, at = 80L)) // duplicate
        val bus = FakeDomainEventBus()
        AssistAttributionUseCase(svc, bus, window = DamageHistoryWindow(durationTicks = 100)).handle(kill(atTick = 100L))
        val events = bus.publishedOfType<AssistCredited>()
        assertThat(events.map { it.player }).containsExactlyInAnyOrder(assistA, assistB)
    }

    @Test
    fun `killer is excluded from assist credits`() {
        val svc = InMemoryDamageHistoryService()
        svc.record(victim, DamageEntry(killer, 5.0, at = 50L))     // killer attacked first
        svc.record(victim, DamageEntry(assistA, 3.0, at = 70L))
        val bus = FakeDomainEventBus()
        AssistAttributionUseCase(svc, bus).handle(kill(atTick = 100L))
        val events = bus.publishedOfType<AssistCredited>()
        assertThat(events.map { it.player }).containsExactly(assistA)
    }
}
