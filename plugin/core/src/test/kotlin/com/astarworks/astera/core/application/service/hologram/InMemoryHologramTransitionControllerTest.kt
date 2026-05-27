package com.astarworks.astera.core.application.service.hologram

import com.astarworks.astera.core.application.port.outbound.hologram.HologramTransitionError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.event.HologramTransitionCancelled
import com.astarworks.astera.core.domain.event.HologramTransitionCompleted
import com.astarworks.astera.core.domain.event.HologramTransitionStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.hologram.Hologram
import com.astarworks.astera.core.domain.hologram.HologramColor
import com.astarworks.astera.core.domain.hologram.HologramId
import com.astarworks.astera.core.domain.hologram.HologramKind
import com.astarworks.astera.core.domain.hologram.HologramTransform
import com.astarworks.astera.core.domain.hologram.ItemDisplayMode
import com.astarworks.astera.core.domain.hologram.transition.HologramTimeline
import com.astarworks.astera.core.domain.hologram.transition.HologramTransition
import com.astarworks.astera.core.domain.hologram.transition.LoopMode
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeHologramGateway
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryHologramTransitionControllerTest {

    private val worldId = WorldId("world")
    private val origin = Vec3(0.0, 64.0, 0.0)

    private fun spawnText(gateway: FakeHologramGateway): com.astarworks.astera.core.domain.hologram.HologramHandle {
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Text(lines = listOf(MessageKey("astera.test.welcome"))),
        )
        return (gateway.spawn(hologram) as Result.Success).value
    }

    private fun spawnItem(gateway: FakeHologramGateway): com.astarworks.astera.core.domain.hologram.HologramHandle {
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Item(itemId = ItemId("example-sword")),
        )
        return (gateway.spawn(hologram) as Result.Success).value
    }

    private fun spawnBlock(gateway: FakeHologramGateway): com.astarworks.astera.core.domain.hologram.HologramHandle {
        val hologram = Hologram(
            id = HologramId.random(),
            worldId = worldId,
            position = origin,
            kind = HologramKind.Block(blockKey = "minecraft:stone"),
        )
        return (gateway.spawn(hologram) as Result.Success).value
    }

    @Test
    fun `play returns UnknownHologram for a handle the gateway does not know`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val unknown = com.astarworks.astera.core.domain.hologram.HologramHandle("never-spawned")
            val result = controller.play(unknown, HologramTransition.ShadowRadius(5f, 10, Easing.LINEAR))
            assertThat(result.isFailure).isTrue()
            assertThat((result as Result.Failure).error)
                .isInstanceOf(HologramTransitionError.UnknownHologram::class.java)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `play returns TypeMismatch when a Text transition targets an Item hologram`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val itemHandle = spawnItem(gateway)
            val transition = HologramTransition.TextOpacity(127, 10, Easing.LINEAR)
            val result = controller.play(itemHandle, transition)
            assertThat(result.isFailure).isTrue()
            val err = (result as Result.Failure).error
            assertThat(err).isInstanceOf(HologramTransitionError.TypeMismatch::class.java)
            val mismatch = err as HologramTransitionError.TypeMismatch
            assertThat(mismatch.expected).isEqualTo(HologramKind.Text::class)
            assertThat(mismatch.actual).isEqualTo(HologramKind.Item::class)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `play of a smooth ShadowRadius transition lerps from 0 to target over duration`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val transition = HologramTransition.ShadowRadius(10f, durationTicks = 10, easing = Easing.LINEAR)
            controller.play(handle, transition)
            // First tick: factor = 1/10 = 0.1, value = 1.0
            scheduler.advanceTicks(1)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(1f, org.assertj.core.data.Offset.offset(0.01f))
            // Tick 5: factor = 5/10 = 0.5, value = 5.0
            scheduler.advanceTicks(4)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(5f, org.assertj.core.data.Offset.offset(0.01f))
            // Tick 10: complete
            scheduler.advanceTicks(5)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(10f, org.assertj.core.data.Offset.offset(0.01f))
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `play publishes HologramTransitionStarted immediately`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val result = controller.play(handle, HologramTransition.ShadowRadius(1f, 5, Easing.LINEAR))
            assertThat(result.isSuccess).isTrue()
            val started = bus.publishedOfType<HologramTransitionStarted>()
            assertThat(started).hasSize(1)
            assertThat(started.first().handle).isEqualTo(handle)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `cancel removes the transition and publishes Cancelled with reasonKind manual`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val play = controller.play(handle, HologramTransition.ShadowRadius(10f, 100, Easing.LINEAR))
            val transitionHandle = (play as Result.Success).value
            assertThat(controller.active(handle)).contains(transitionHandle)
            val cancelResult = controller.cancel(transitionHandle)
            assertThat(cancelResult.isSuccess).isTrue()
            assertThat(controller.active(handle)).isEmpty()
            val cancelled = bus.publishedOfType<HologramTransitionCancelled>()
            assertThat(cancelled).hasSize(1)
            assertThat(cancelled.first().reasonKind).isEqualTo("manual")
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `cancel of unknown handle is idempotent success`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val unknown = com.astarworks.astera.core.domain.hologram.transition.HologramTransitionHandle("never-existed")
            val result = controller.cancel(unknown)
            assertThat(result.isSuccess).isTrue()
            // No event published.
            assertThat(bus.publishedOfType<HologramTransitionCancelled>()).isEmpty()
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `cancelAll cancels every transition on the hologram with reasonKind hologram_despawned`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            controller.play(handle, HologramTransition.ShadowRadius(5f, 100, Easing.LINEAR))
            controller.play(handle, HologramTransition.ShadowStrength(0.5f, 100, Easing.LINEAR))
            controller.play(handle, HologramTransition.ViewRange(100f, 100, Easing.LINEAR))
            assertThat(controller.active(handle)).hasSize(3)
            controller.cancelAll(handle)
            assertThat(controller.active(handle)).isEmpty()
            val cancelled = bus.publishedOfType<HologramTransitionCancelled>()
            assertThat(cancelled).hasSize(3)
            assertThat(cancelled).allMatch { it.reasonKind == "hologram_despawned" }
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `Composite transition runs all children in parallel and completes only when every child finishes`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val composite = HologramTransition.Composite(
                listOf(
                    HologramTransition.ShadowRadius(10f, 5, Easing.LINEAR),
                    HologramTransition.ShadowStrength(0.5f, 10, Easing.LINEAR),
                ),
            )
            controller.play(handle, composite)
            // At tick 5: short child completes, long one is mid-way.
            scheduler.advanceTicks(5)
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).isEmpty()
            // At tick 10: both done.
            scheduler.advanceTicks(5)
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(10f, org.assertj.core.data.Offset.offset(0.01f))
            assertThat(gateway.current(handle)?.shadowStrength).isCloseTo(0.5f, org.assertj.core.data.Offset.offset(0.01f))
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `Timeline runs transitions in sequence — next starts only after prev completes`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val timeline = HologramTimeline(
                transitions = listOf(
                    HologramTransition.ShadowRadius(5f, durationTicks = 5, easing = Easing.LINEAR),
                    HologramTransition.ShadowRadius(0f, durationTicks = 5, easing = Easing.LINEAR),
                ),
            )
            controller.play(handle, timeline)
            // At tick 5: first transition done, value = 5.
            scheduler.advanceTicks(5)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(5f, org.assertj.core.data.Offset.offset(0.01f))
            // At tick 10 (5 + 5 second transition): value = 0.
            scheduler.advanceTicks(5)
            assertThat(gateway.current(handle)?.shadowRadius).isCloseTo(0f, org.assertj.core.data.Offset.offset(0.01f))
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `Timeline LoopMode Loop with times 3 runs the body 3 iterations total`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val timeline = HologramTimeline(
                transitions = listOf(HologramTransition.ShadowRadius(10f, durationTicks = 2, easing = Easing.LINEAR)),
                loopMode = LoopMode.Loop(times = 3),
            )
            controller.play(handle, timeline)
            // Each iteration takes 2 ticks. 3 iterations = 6 ticks total.
            scheduler.advanceTicks(6)
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `Timeline LoopMode PingPong with times 1 runs forward then backward`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val timeline = HologramTimeline(
                transitions = listOf(
                    HologramTransition.ShadowRadius(5f, durationTicks = 2, easing = Easing.LINEAR),
                    HologramTransition.ShadowRadius(10f, durationTicks = 2, easing = Easing.LINEAR),
                ),
                loopMode = LoopMode.PingPong(times = 1),
            )
            controller.play(handle, timeline)
            // Forward: tick 2 → first child done, tick 4 → second child done.
            // Backward begins from second (index 1), tick 6 → done, tick 8 → first done.
            // Total = 8 ticks for one cycle (forward + back).
            scheduler.advanceTicks(8)
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `Timeline LoopMode PlayOnce completes after the final transition`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val timeline = HologramTimeline(
                transitions = listOf(
                    HologramTransition.ShadowRadius(3f, durationTicks = 1, easing = Easing.LINEAR),
                ),
                loopMode = LoopMode.PlayOnce,
            )
            controller.play(handle, timeline)
            scheduler.advanceTicks(1)
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `categorical TextContent fires once after delay and completes`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val transition = HologramTransition.TextContent(
                targetLines = listOf(MessageKey("astera.test.updated")),
                delayTicks = 3,
            )
            controller.play(handle, transition)
            // Before delay: text content unchanged.
            scheduler.advanceTicks(2)
            val current2 = gateway.current(handle)?.kind as HologramKind.Text
            assertThat(current2.lines.first().value).isEqualTo("astera.test.welcome")
            // At delay tick: applied.
            scheduler.advanceTicks(1)
            val current3 = gateway.current(handle)?.kind as HologramKind.Text
            assertThat(current3.lines.first().value).isEqualTo("astera.test.updated")
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `categorical ItemSwap on Item hologram changes the itemId`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnItem(gateway)
            controller.play(handle, HologramTransition.ItemSwap(ItemId("lightning-blade"), delayTicks = 0))
            scheduler.advanceTicks(1)
            val kind = gateway.current(handle)?.kind as HologramKind.Item
            assertThat(kind.itemId).isEqualTo(ItemId("lightning-blade"))
            assertThat(bus.publishedOfType<HologramTransitionCompleted>()).hasSize(1)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `categorical BlockData on Block hologram changes the blockKey`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnBlock(gateway)
            controller.play(handle, HologramTransition.BlockData("minecraft:gold_block", delayTicks = 0))
            scheduler.advanceTicks(1)
            val kind = gateway.current(handle)?.kind as HologramKind.Block
            assertThat(kind.blockKey).isEqualTo("minecraft:gold_block")
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `shutdown cancels every active transition with reasonKind scheduler_shutdown`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        val handle = spawnText(gateway)
        controller.play(handle, HologramTransition.ShadowRadius(5f, 100, Easing.LINEAR))
        controller.play(handle, HologramTransition.ShadowStrength(0.5f, 100, Easing.LINEAR))
        controller.shutdown()
        val cancelled = bus.publishedOfType<HologramTransitionCancelled>()
        assertThat(cancelled).hasSize(2)
        assertThat(cancelled).allMatch { it.reasonKind == "scheduler_shutdown" }
    }

    @Test
    fun `composite with TypeMismatch child returns TypeMismatch synchronously`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val itemHandle = spawnItem(gateway)
            val composite = HologramTransition.Composite(
                listOf(
                    HologramTransition.ShadowRadius(5f, 10, Easing.LINEAR), // OK
                    HologramTransition.TextOpacity(0, 10, Easing.LINEAR), // type mismatch
                ),
            )
            val result = controller.play(itemHandle, composite)
            assertThat(result.isFailure).isTrue()
            assertThat((result as Result.Failure).error)
                .isInstanceOf(HologramTransitionError.TypeMismatch::class.java)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `play of Transform tweens translation channel-wise over duration`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val target = HologramTransform(translation = Vec3(10.0, 20.0, 30.0))
            controller.play(handle, HologramTransition.Transform(target, durationTicks = 10, easing = Easing.LINEAR))
            scheduler.advanceTicks(5)
            val midTransform = gateway.current(handle)?.transform
            assertThat(midTransform).isNotNull
            // factor = 0.5, mid translation = (5, 10, 15).
            assertThat(midTransform!!.translation.x).isCloseTo(5.0, org.assertj.core.data.Offset.offset(0.01))
            assertThat(midTransform.translation.y).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01))
            assertThat(midTransform.translation.z).isCloseTo(15.0, org.assertj.core.data.Offset.offset(0.01))
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `categorical ItemDisplayModeSet on Item hologram changes the displayMode`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnItem(gateway)
            controller.play(handle, HologramTransition.ItemDisplayModeSet(ItemDisplayMode.HEAD, delayTicks = 0))
            scheduler.advanceTicks(1)
            val kind = gateway.current(handle)?.kind as HologramKind.Item
            assertThat(kind.displayMode).isEqualTo(ItemDisplayMode.HEAD)
        } finally {
            controller.shutdown()
        }
    }

    @Test
    fun `smooth GlowColor lerps RGBA channel-wise`() {
        val gateway = FakeHologramGateway()
        val scheduler = FakeScheduler()
        val bus = FakeDomainEventBus()
        val controller = InMemoryHologramTransitionController(gateway, scheduler, bus)
        try {
            val handle = spawnText(gateway)
            val target = HologramColor(red = 100, green = 50, blue = 25, alpha = 80)
            controller.play(handle, HologramTransition.GlowColor(target, durationTicks = 10, easing = Easing.LINEAR))
            scheduler.advanceTicks(5)
            // Initial glow is null → (0,0,0,0); at factor 0.5 the channel-wise lerp gives (50, 25, 12-13, 40).
            val midColor = gateway.current(handle)?.glowColor
            assertThat(midColor).isNotNull
            assertThat(midColor!!.red).isEqualTo(50.toByte())
            assertThat(midColor.green).isEqualTo(25.toByte())
            assertThat(midColor.alpha).isEqualTo(40.toByte())
        } finally {
            controller.shutdown()
        }
    }
}
