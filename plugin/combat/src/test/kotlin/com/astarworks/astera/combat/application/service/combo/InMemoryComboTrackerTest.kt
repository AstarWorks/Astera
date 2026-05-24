package com.astarworks.astera.combat.application.service.combo

import com.astarworks.astera.combat.domain.combo.ComboPattern
import com.astarworks.astera.combat.domain.combo.ComboRoute
import com.astarworks.astera.combat.domain.combo.ComboRouteId
import com.astarworks.astera.combat.domain.event.ComboRouteCompleted
import com.astarworks.astera.combat.domain.event.ComboStepRecorded
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryComboTrackerTest {

    private val attacker = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())
    private val sword = ItemId("example-sword")
    private val staff = ItemId("example-staff")

    private lateinit var bus: InMemoryDomainEventBus
    private lateinit var tracker: InMemoryComboTracker
    private lateinit var captured: MutableList<DomainEvent>

    @BeforeEach
    fun setup() {
        bus = InMemoryDomainEventBus()
        tracker = InMemoryComboTracker(bus)
        captured = mutableListOf()
        bus.subscribe(DomainEvent::class) { captured += it }
    }

    @Test
    fun `recordHit appends step and publishes ComboStepRecorded`() {
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 10)

        val state = tracker.stateOf(attacker, nowTick = 10)
        assertThat(state).isNotNull
        assertThat(state!!.streakCount).isEqualTo(1)
        assertThat(captured.filterIsInstance<ComboStepRecorded>()).hasSize(1)
    }

    @Test
    fun `switching weapon resets combo`() {
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 10)
        tracker.recordHit(attacker, staff, victim, damageDealt = 5.0, atTick = 11)

        val state = tracker.stateOf(attacker, nowTick = 11)
        assertThat(state).isNotNull
        assertThat(state!!.weaponId).isEqualTo(staff)
        assertThat(state.streakCount).isEqualTo(1)
    }

    @Test
    fun `expired state is dropped on read`() {
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 10)
        // Default timeout is 60 ticks; nowTick = 200 is well past expiry.
        assertThat(tracker.stateOf(attacker, nowTick = 200)).isNull()
    }

    @Test
    fun `route completion publishes ComboRouteCompleted and resets state`() {
        val route = ComboRoute(
            id = ComboRouteId("sword-3-hit"),
            weaponId = sword,
            pattern = ComboPattern.HitCount(3),
            finisher = SkillId("uppercut"),
            bonusDamageMultiplier = 1.5,
        )
        tracker.registerRoute(route)

        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 0)
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 5)
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 10)

        val completed = captured.filterIsInstance<ComboRouteCompleted>()
        assertThat(completed).hasSize(1)
        assertThat(completed.first().routeId).isEqualTo(ComboRouteId("sword-3-hit"))
        assertThat(completed.first().skill).isEqualTo(SkillId("uppercut"))
        assertThat(completed.first().bonusDamageMultiplier).isEqualTo(1.5)
        // State must reset after firing so the player has to rebuild.
        assertThat(tracker.stateOf(attacker, nowTick = 10)).isNull()
    }

    @Test
    fun `reset drops the in-flight state without firing events`() {
        tracker.recordHit(attacker, sword, victim, damageDealt = 5.0, atTick = 0)
        captured.clear()

        tracker.reset(attacker)

        assertThat(tracker.stateOf(attacker, nowTick = 0)).isNull()
        assertThat(captured.filterIsInstance<ComboRouteCompleted>()).isEmpty()
    }

    @Test
    fun `unregisterAll clears the route map`() {
        tracker.registerRoute(
            ComboRoute(
                id = ComboRouteId("a"),
                weaponId = sword,
                pattern = ComboPattern.HitCount(1),
                finisher = SkillId("s"),
            ),
        )
        tracker.unregisterAll()
        assertThat(tracker.allRoutes()).isEmpty()
    }
}
