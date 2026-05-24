package com.astarworks.astera.combat.application.service.targetlock

import com.astarworks.astera.combat.domain.event.TargetLockAcquired
import com.astarworks.astera.combat.domain.event.TargetLockReleased
import com.astarworks.astera.combat.domain.targetlock.TargetLockConfig
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryTargetLockControllerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val bow = ItemId("example-bow")
    private val gun = ItemId("example-gun")
    private val target1 = EntityHandle(UUID.randomUUID(), "test")
    private val target2 = EntityHandle(UUID.randomUUID(), "test")

    private lateinit var bus: InMemoryDomainEventBus
    private lateinit var ctrl: InMemoryTargetLockController
    private lateinit var captured: MutableList<DomainEvent>

    @BeforeEach
    fun setup() {
        bus = InMemoryDomainEventBus()
        ctrl = InMemoryTargetLockController(
            bus,
            TargetLockConfig(
                acquisitionTicks = 3,
                lockLifetimeTicks = 20,
                damageBonusMultiplier = 1.3,
            ),
        )
        captured = mutableListOf()
        bus.subscribe(DomainEvent::class) { captured += it }
    }

    @Test
    fun `acquisition fires after threshold ticks`() {
        ctrl.tickAim(player, bow, target1, atTick = 0)
        ctrl.tickAim(player, bow, target1, atTick = 1)
        // Not yet at threshold (3).
        assertThat(captured.filterIsInstance<TargetLockAcquired>()).isEmpty()
        ctrl.tickAim(player, bow, target1, atTick = 2)
        assertThat(captured.filterIsInstance<TargetLockAcquired>()).hasSize(1)
    }

    @Test
    fun `bonus consumes exactly once per acquisition`() {
        repeat(3) { ctrl.tickAim(player, bow, target1, atTick = it.toLong()) }
        assertThat(ctrl.consumeBonusFor(player, target1, nowTick = 5)).isEqualTo(1.3)
        // Second consumption — no bonus left, lock still active.
        assertThat(ctrl.consumeBonusFor(player, target1, nowTick = 5)).isEqualTo(1.0)
        val s = ctrl.stateOf(player, nowTick = 5)
        assertThat(s).isNotNull
        assertThat(s!!.isLocked).isTrue()
    }

    @Test
    fun `switching weapon drops lock with WeaponSwitched`() {
        repeat(3) { ctrl.tickAim(player, bow, target1, atTick = it.toLong()) }
        captured.clear()
        ctrl.tickAim(player, gun, target1, atTick = 5)
        val released = captured.filterIsInstance<TargetLockReleased>()
        assertThat(released).hasSize(1)
        assertThat(released.first().reason).isEqualTo(TargetLockReleased.Reason.WeaponSwitched)
    }

    @Test
    fun `switching target drops lock with TargetLost`() {
        repeat(3) { ctrl.tickAim(player, bow, target1, atTick = it.toLong()) }
        captured.clear()
        ctrl.tickAim(player, bow, target2, atTick = 5)
        val released = captured.filterIsInstance<TargetLockReleased>()
        assertThat(released).hasSize(1)
        assertThat(released.first().reason).isEqualTo(TargetLockReleased.Reason.TargetLost)
    }

    @Test
    fun `lifetime expiry releases lock on stateOf`() {
        repeat(3) { ctrl.tickAim(player, bow, target1, atTick = it.toLong()) }
        // Lock acquired at tick 2, lifetime 20, so tick 23 = still alive, 24 = expired.
        assertThat(ctrl.stateOf(player, nowTick = 22)).isNotNull
        assertThat(ctrl.stateOf(player, nowTick = 25)).isNull()
        val released = captured.filterIsInstance<TargetLockReleased>()
        assertThat(released.last().reason).isEqualTo(TargetLockReleased.Reason.Expired)
    }

    @Test
    fun `breakAim before acquisition resets aimingTicks`() {
        ctrl.tickAim(player, bow, target1, atTick = 0)
        ctrl.tickAim(player, bow, target1, atTick = 1)
        ctrl.breakAim(player, atTick = 2)
        // Now aim resumes — needs 3 more consecutive ticks.
        ctrl.tickAim(player, bow, target1, atTick = 3)
        ctrl.tickAim(player, bow, target1, atTick = 4)
        assertThat(captured.filterIsInstance<TargetLockAcquired>()).isEmpty()
        ctrl.tickAim(player, bow, target1, atTick = 5)
        assertThat(captured.filterIsInstance<TargetLockAcquired>()).hasSize(1)
    }

    @Test
    fun `manual release publishes ManualRelease event`() {
        repeat(3) { ctrl.tickAim(player, bow, target1, atTick = it.toLong()) }
        captured.clear()
        ctrl.release(player, atTick = 10)
        val released = captured.filterIsInstance<TargetLockReleased>()
        assertThat(released).hasSize(1)
        assertThat(released.first().reason).isEqualTo(TargetLockReleased.Reason.ManualRelease)
    }
}
