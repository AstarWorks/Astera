package com.astarworks.astera.core.application.service.hologram

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.event.HologramTransitionCancelled
import com.astarworks.astera.core.domain.event.HologramTransitionCompleted
import com.astarworks.astera.core.domain.event.HologramTransitionStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramId
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.transition.HologramTransition
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHologramGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HologramTransitionEventTest {

    private val worldId = WorldId("world")
    private val origin = Vec3(0.0, 64.0, 0.0)

    private fun spawnHandle(gateway: FakeHologramGateway) =
        (gateway.spawn(
            Hologram(
                id = HologramId.random(),
                worldId = worldId,
                position = origin,
                kind = HologramKind.Text(lines = listOf(MessageKey("astera.test.welcome"))),
            ),
        ) as Result.Success).value

    @Test
    fun `HologramTransitionStarted fires immediately on play with correct fields`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler(startTick = 42L)
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnHandle(gateway)
            val play = controller.play(handle, HologramTransition.ShadowRadius(10f, 5, Easing.LINEAR))
            val transitionHandle = (play as Result.Success).value
            val started = bus.publishedOfType<HologramTransitionStarted>()
            assertThat(started).hasSize(1)
            assertThat(started.first().handle).isEqualTo(handle)
            assertThat(started.first().transitionHandle).isEqualTo(transitionHandle)
            assertThat(started.first().atTick).isEqualTo(42L)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `HologramTransitionCompleted fires after duration with correct handles`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnHandle(gateway)
            val play = controller.play(handle, HologramTransition.ShadowRadius(10f, 3, Easing.LINEAR))
            val transitionHandle = (play as Result.Success).value
            scheduler.advanceTicks(3)
            val completed = bus.publishedOfType<HologramTransitionCompleted>()
            assertThat(completed).hasSize(1)
            assertThat(completed.first().handle).isEqualTo(handle)
            assertThat(completed.first().transitionHandle).isEqualTo(transitionHandle)
            // No cancellation event for natural completion.
            assertThat(bus.publishedOfType<HologramTransitionCancelled>()).isEmpty()
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `HologramTransitionCancelled fires with manual reason on explicit cancel`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnHandle(gateway)
            val play = controller.play(handle, HologramTransition.ShadowRadius(10f, 100, Easing.LINEAR))
            val transitionHandle = (play as Result.Success).value
            controller.cancel(transitionHandle)
            val cancelled = bus.publishedOfType<HologramTransitionCancelled>()
            assertThat(cancelled).hasSize(1)
            assertThat(cancelled.first().reasonKind).isEqualTo("manual")
            assertThat(cancelled.first().transitionHandle).isEqualTo(transitionHandle)
        } finally {
            controller.shutdown()
        }
    }
}
