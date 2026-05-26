package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.guidepth.GuiAnimationError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.event.SlotAnimationCancelled
import com.astarworks.astera.core.domain.event.SlotAnimationCompleted
import com.astarworks.astera.core.domain.event.SlotAnimationStarted
import com.astarworks.astera.core.domain.gui.GuiId
import com.astarworks.astera.core.domain.gui.SlotIndex
import com.astarworks.astera.core.domain.guidepth.AnimationHandle
import com.astarworks.astera.core.domain.guidepth.HoverAnimation
import com.astarworks.astera.core.domain.guidepth.SlotAnimation
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryGuiAnimationControllerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val guiId = GuiId("test-gui")
    private val slot = SlotIndex(13)

    private data class Captured(
        val started: MutableList<SlotAnimationStarted> = mutableListOf(),
        val completed: MutableList<SlotAnimationCompleted> = mutableListOf(),
        val cancelled: MutableList<SlotAnimationCancelled> = mutableListOf(),
    )

    private fun newController(tick: Long = 42L): Pair<InMemoryGuiAnimationController, Captured> {
        val bus = InMemoryDomainEventBus()
        val cap = Captured()
        bus.subscribe(SlotAnimationStarted::class) { cap.started.add(it) }
        bus.subscribe(SlotAnimationCompleted::class) { cap.completed.add(it) }
        bus.subscribe(SlotAnimationCancelled::class) { cap.cancelled.add(it) }
        return InMemoryGuiAnimationController(bus) { tick } to cap
    }

    @Test
    fun `animateSlot publishes SlotAnimationStarted with kind tag`() {
        val (c, cap) = newController()
        val r = c.animateSlot(playerId, guiId, slot, SlotAnimation.Fade(0.0, 1.0, 20L, Easing.LINEAR))
        assertThat(r.isSuccess).isTrue()
        val handle = (r as Result.Success).value
        assertThat(cap.started).hasSize(1)
        assertThat(cap.started[0].animationKind).isEqualTo("fade")
        assertThat(cap.started[0].handle).isEqualTo(handle)
        assertThat(cap.started[0].atTick).isEqualTo(42L)
    }

    @Test
    fun `animateSlot tags each variant correctly`() {
        val (c, cap) = newController()
        c.animateSlot(playerId, guiId, SlotIndex(1), SlotAnimation.Fade(0.0, 1.0, 5L, Easing.LINEAR))
        c.animateSlot(playerId, guiId, SlotIndex(2), SlotAnimation.Slide(SlotIndex(0), SlotIndex(8), 5L, Easing.LINEAR))
        c.animateSlot(playerId, guiId, SlotIndex(3), SlotAnimation.Bounce(amplitude = 0.5, durationTicks = 5L))
        c.animateSlot(playerId, guiId, SlotIndex(4), SlotAnimation.Pulse(1.0, 1.2, 5L))
        assertThat(cap.started.map { it.animationKind })
            .containsExactly("fade", "slide", "bounce", "pulse")
    }

    @Test
    fun `animateSlot on same slot supersedes prior handle with cancel event`() {
        val (c, cap) = newController()
        val first = (c.animateSlot(playerId, guiId, slot, SlotAnimation.Fade(0.0, 1.0, 20L, Easing.LINEAR)) as Result.Success).value
        val second = (c.animateSlot(playerId, guiId, slot, SlotAnimation.Bounce(0.5, 10L)) as Result.Success).value
        assertThat(first).isNotEqualTo(second)
        assertThat(cap.cancelled).hasSize(1)
        assertThat(cap.cancelled[0].handle).isEqualTo(first)
        assertThat(cap.cancelled[0].reasonKind).isEqualTo("superseded")
        assertThat(c.activeCount()).isEqualTo(1)
    }

    @Test
    fun `cancel publishes explicit reason and clears active`() {
        val (c, cap) = newController()
        val handle = (c.animateSlot(playerId, guiId, slot, SlotAnimation.Fade(0.0, 1.0, 20L, Easing.LINEAR)) as Result.Success).value
        c.cancel(handle)
        assertThat(cap.cancelled).hasSize(1)
        assertThat(cap.cancelled[0].handle).isEqualTo(handle)
        assertThat(cap.cancelled[0].reasonKind).isEqualTo("explicit")
        assertThat(c.activeCount()).isEqualTo(0)
    }

    @Test
    fun `cancel on unknown handle is idempotent`() {
        val (c, cap) = newController()
        val r = c.cancel(AnimationHandle("never-issued"))
        assertThat(r.isSuccess).isTrue()
        assertThat(cap.cancelled).isEmpty()
    }

    @Test
    fun `simulateComplete publishes SlotAnimationCompleted and clears active`() {
        val (c, cap) = newController()
        val handle = (c.animateSlot(playerId, guiId, slot, SlotAnimation.Bounce(0.5, 10L)) as Result.Success).value
        c.simulateComplete(handle)
        assertThat(cap.completed).hasSize(1)
        assertThat(cap.completed[0].handle).isEqualTo(handle)
        assertThat(c.activeCount()).isEqualTo(0)
        assertThat(cap.cancelled).isEmpty() // completed != cancelled
    }

    @Test
    fun `animateHover registers with hover kind`() {
        val (c, cap) = newController()
        val r = c.animateHover(playerId, guiId, slot, HoverAnimation.Scale(1.2, 4L, Easing.LINEAR))
        assertThat(r.isSuccess).isTrue()
        assertThat(cap.started.last().animationKind).isEqualTo("hover")
    }

    @Test
    fun `animateScroll rejects negative duration`() {
        val (c, _) = newController()
        val r = c.animateScroll(playerId, guiId, fromScroll = 0, toScroll = 10, durationTicks = -1L, easing = Easing.LINEAR)
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(GuiAnimationError.InvalidDuration::class.java)
    }

    @Test
    fun `animateScroll supersedes prior scroll on the same gui`() {
        val (c, cap) = newController()
        val first = (c.animateScroll(playerId, guiId, 0, 10, 20L, Easing.LINEAR) as Result.Success).value
        val second = (c.animateScroll(playerId, guiId, 10, 0, 20L, Easing.LINEAR) as Result.Success).value
        assertThat(first).isNotEqualTo(second)
        assertThat(cap.cancelled).hasSize(1)
        assertThat(cap.cancelled[0].handle).isEqualTo(first)
        assertThat(cap.cancelled[0].reasonKind).isEqualTo("superseded")
        assertThat(cap.started.last().animationKind).isEqualTo("scroll")
    }
}
