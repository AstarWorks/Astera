package com.astarworks.astera.combat.application.eventbus.listeners.hud

import com.astarworks.astera.combat.domain.status.StatusEffectInstance
import com.astarworks.astera.combat.domain.status.StatusEffectSpec
import com.astarworks.astera.combat.domain.status.StatusKind
import com.astarworks.astera.combat.testfixtures.FakeStatusEffectContainer
import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryEgoGauge
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.event.ResourcePackStatusChanged
import com.astarworks.astera.core.domain.hud.HudColor
import com.astarworks.astera.core.domain.hud.HudComponent
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.ResourcePackStatus
import com.astarworks.astera.core.domain.hud.status.StatusEffectHudLayout
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.testfixtures.FakeHudGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Status effects on the action bar: good green, bad red, stacks and whole seconds left. */
class HudStatusEffectReadoutListenerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val sessions = InMemoryPlayerSessionRepository().apply { upsert(PlayerSession(player, SessionState.InMatch)) }
    private val effects = FakeStatusEffectContainer()
    private val hud = FakeHudGateway()
    private val scheduler = FakeScheduler()

    private fun effect(id: String, kind: StatusKind, stacks: Int, remainingTicks: Int) = StatusEffectInstance(
        spec = StatusEffectSpec(
            id = StatusEffectId(id),
            displayNameKey = MessageKey("astera.status.$id"),
            kind = kind,
            magnitude = 1.0,
            stackable = true,
            maxStacks = 5,
        ),
        targetPlayerId = player,
        remainingTicks = remainingTicks,
        currentStacks = stacks,
        appliedAtTick = 0L,
    )

    /** Latest action bar sent: a same-region redraw is an `Updated` op, not a new `Shown`. */
    private fun shownBar(): HudComponent.Composite {
        val layout = hud.operations.last {
            it is FakeHudGateway.HudOp.Shown || it is FakeHudGateway.HudOp.Updated
        }.let { op ->
            when (op) {
                is FakeHudGateway.HudOp.Shown -> op.layout
                is FakeHudGateway.HudOp.Updated -> op.layout
                else -> error("unreachable")
            }
        }
        return (layout as HudLayout.ActionBar).component as HudComponent.Composite
    }

    @Test
    fun `harmful effects are red, beneficial green, with stacks and seconds rounded up`() {
        effects.apply(effect("burn", StatusKind.BURN, stacks = 2, remainingTicks = 61))
        effects.apply(effect("haste", StatusKind.SPEED_UP, stacks = 1, remainingTicks = 20))
        HudStatusEffectReadoutListener(sessions, hud, scheduler, effects)

        scheduler.advanceTicks(10)

        val parts = shownBar().parts.filterIsInstance<HudComponent.Text>()
        val burn = parts.first { it.messageKey.value == "astera.status.burn" }
        val haste = parts.first { it.messageKey.value == "astera.status.haste" }
        assertThat(burn.color).isEqualTo(HudColor.RED)
        assertThat(haste.color).isEqualTo(HudColor.GREEN)
        assertThat(parts.filter { it.messageKey.value == "astera.hud.status.stacks" }.map { it.placeholders["stacks"] })
            .containsExactly("2")
        assertThat(parts.filter { it.messageKey.value == "astera.hud.status.seconds" }.map { it.placeholders["seconds"] })
            .containsExactly("4", "1")
        assertThat(parts.count { it.messageKey.value == "astera.hud.status.separator" }).isEqualTo(1)
    }

    @Test
    fun `loaded pack suppresses supported effect text and declining restores it`() {
        effects.apply(effect("burn", StatusKind.BURN, stacks = 2, remainingTicks = 61))
        val listener = HudStatusEffectReadoutListener(sessions, hud, scheduler, effects)
        listener.onPackStatus(ResourcePackStatusChanged(
            player, ResourcePackStatus.LOADED,
        ))
        scheduler.advanceTicks(10)
        assertThat(hud.operations).isEmpty()
        assertThat(listener.entriesFor(player)).containsExactly(
            StatusEffectHudLayout.Entry("burn", 61, 2),
        )
        listener.onPackStatus(ResourcePackStatusChanged(
            player, ResourcePackStatus.DECLINED,
        ))
        scheduler.advanceTicks(10)
        assertThat(shownBar().parts.filterIsInstance<HudComponent.Text>().map { it.messageKey.value })
            .contains("astera.status.burn")
    }

    @Test
    fun `loaded pack keeps EGO and unauthored effects in the text readout`() {
        effects.apply(effect("burn", StatusKind.BURN, stacks = 1, remainingTicks = 20))
        effects.apply(effect("haste", StatusKind.SPEED_UP, stacks = 1, remainingTicks = 20))
        val gauge = InMemoryEgoGauge(
            scheduler, InMemoryDomainEventBus(),
        )
        gauge.gain(player, 5)
        val listener = HudStatusEffectReadoutListener(sessions, hud, scheduler, effects, egoGauge = gauge)
        listener.onPackStatus(ResourcePackStatusChanged(
            player, ResourcePackStatus.LOADED,
        ))
        scheduler.advanceTicks(10)
        assertThat(shownBar().parts.filterIsInstance<HudComponent.Text>().map { it.messageKey.value })
            .contains("astera.hud.ego.gauge", "astera.status.haste")
            .doesNotContain("astera.status.burn")
        listener.onQuit(PlayerQuitServer(player))
        scheduler.advanceTicks(10)
        assertThat(shownBar().parts.filterIsInstance<HudComponent.Text>().map { it.messageKey.value })
            .contains("astera.status.burn")
    }

    @Test
    fun `no effects means no readout`() {
        HudStatusEffectReadoutListener(sessions, hud, scheduler, effects)

        scheduler.advanceTicks(10)

        assertThat(hud.operations).isEmpty()
    }
}
