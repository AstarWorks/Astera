package com.astarworks.astera.combat.application.service.focus

import com.astarworks.astera.combat.domain.event.FocusFireActivated
import com.astarworks.astera.combat.domain.event.FocusFireDeactivated
import com.astarworks.astera.combat.domain.focus.FocusFireConfig
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryFocusFireTrackerTest {

    private val victim = PlayerId(UUID.randomUUID())
    private val a = PlayerId(UUID.randomUUID())
    private val b = PlayerId(UUID.randomUUID())
    private val c = PlayerId(UUID.randomUUID())

    private lateinit var bus: InMemoryDomainEventBus
    private lateinit var tracker: InMemoryFocusFireTracker
    private lateinit var captured: MutableList<DomainEvent>

    @BeforeEach
    fun setup() {
        bus = InMemoryDomainEventBus()
        tracker = InMemoryFocusFireTracker(
            bus,
            FocusFireConfig(minAttackers = 3, windowTicks = 100, bonusMultiplier = 1.2),
        )
        captured = mutableListOf()
        bus.subscribe(DomainEvent::class) { captured += it }
    }

    @Test
    fun `single attacker does not trigger`() {
        tracker.recordHit(a, victim, damageDealt = 5.0, atTick = 0)
        assertThat(captured.filterIsInstance<FocusFireActivated>()).isEmpty()
    }

    @Test
    fun `three attackers within window trigger Activated once`() {
        tracker.recordHit(a, victim, 5.0, atTick = 0)
        tracker.recordHit(b, victim, 5.0, atTick = 10)
        tracker.recordHit(c, victim, 5.0, atTick = 20)

        val acts = captured.filterIsInstance<FocusFireActivated>()
        assertThat(acts).hasSize(1)
        assertThat(acts.first().attackers).containsExactlyInAnyOrder(a, b, c)
        assertThat(acts.first().totalDamage).isEqualTo(15.0)
    }

    @Test
    fun `bonus multiplier only applies to active contributors`() {
        tracker.recordHit(a, victim, 5.0, atTick = 0)
        tracker.recordHit(b, victim, 5.0, atTick = 10)
        tracker.recordHit(c, victim, 5.0, atTick = 20)

        assertThat(tracker.bonusMultiplierFor(a, victim, atTick = 20)).isEqualTo(1.2)
        // Non-contributor doesn't benefit.
        val outsider = PlayerId(UUID.randomUUID())
        assertThat(tracker.bonusMultiplierFor(outsider, victim, atTick = 20)).isEqualTo(1.0)
    }

    @Test
    fun `bonusMultiplierFor returns 1 when below threshold`() {
        tracker.recordHit(a, victim, 5.0, atTick = 0)
        assertThat(tracker.bonusMultiplierFor(a, victim, atTick = 0)).isEqualTo(1.0)
    }

    @Test
    fun `state collapses when all contributions age out`() {
        tracker.recordHit(a, victim, 5.0, atTick = 0)
        tracker.recordHit(b, victim, 5.0, atTick = 5)
        tracker.recordHit(c, victim, 5.0, atTick = 10)
        // Now far in the future — windowTicks=100 means everything is stale.
        val s = tracker.stateOf(victim, nowTick = 1000)
        assertThat(s).isNull()
        // And we published a Deactivated for the prior Active.
        assertThat(captured.filterIsInstance<FocusFireDeactivated>()).hasSize(1)
    }

    @Test
    fun `reset drops state without publishing events`() {
        tracker.recordHit(a, victim, 5.0, atTick = 0)
        tracker.recordHit(b, victim, 5.0, atTick = 5)
        tracker.recordHit(c, victim, 5.0, atTick = 10)
        captured.clear()

        tracker.reset(victim)

        assertThat(tracker.stateOf(victim, nowTick = 10)).isNull()
        assertThat(captured).isEmpty()
    }

    @Test
    fun `rejects self-attack`() {
        assertThat(runCatching {
            tracker.recordHit(victim, victim, 1.0, atTick = 0)
        }.isFailure).isTrue()
    }
}
