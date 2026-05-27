package com.astarworks.astera.core.application.eventbus.listeners.region

import com.astarworks.astera.core.domain.event.PlayerEnteredRegion
import com.astarworks.astera.core.domain.event.PlayerExitedRegion
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeStageRegionIndex
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class RegionTransitionDetectorListenerTest {

    private val world = WorldId("world")
    private val playerId = PlayerId(UUID.randomUUID())

    private fun moved(from: Vec3, to: Vec3): PlayerMoved =
        PlayerMoved(playerId = playerId, from = from, to = to, world = world, atTick = 1L)

    @Test
    fun `first move into a region emits PlayerEnteredRegion`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        listener.handle(moved(from = Vec3(-1.0, 5.0, 5.0), to = Vec3(5.0, 5.0, 5.0)))

        val entered = bus.publishedOfType<PlayerEnteredRegion>()
        assertThat(entered).hasSize(1)
        assertThat(entered.single().regionId).isEqualTo(RegionId("zone-a"))
        assertThat(bus.publishedOfType<PlayerExitedRegion>()).isEmpty()
    }

    @Test
    fun `move out of a region emits PlayerExitedRegion`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        // Seed: player already inside.
        listener.handle(moved(from = Vec3(5.0, 5.0, 5.0), to = Vec3(5.0, 5.0, 5.0)))
        bus.published.clear()

        listener.handle(moved(from = Vec3(5.0, 5.0, 5.0), to = Vec3(50.0, 5.0, 5.0)))

        val exited = bus.publishedOfType<PlayerExitedRegion>()
        assertThat(exited).hasSize(1)
        assertThat(exited.single().regionId).isEqualTo(RegionId("zone-a"))
        assertThat(bus.publishedOfType<PlayerEnteredRegion>()).isEmpty()
    }

    @Test
    fun `staying inside the same region emits nothing`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        listener.handle(moved(Vec3(1.0, 5.0, 5.0), Vec3(2.0, 5.0, 5.0)))
        bus.published.clear()

        listener.handle(moved(Vec3(2.0, 5.0, 5.0), Vec3(3.0, 5.0, 5.0)))

        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `staying outside any region emits nothing`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        listener.handle(moved(Vec3(-5.0, 5.0, 5.0), Vec3(-3.0, 5.0, 5.0)))

        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `simultaneous enter and exit fire both events`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        index.register(world, RegionId("zone-b"), Region(Vec3(20.0, 0.0, 0.0), Vec3(30.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        // Seed inside zone-a.
        listener.handle(moved(Vec3(5.0, 5.0, 5.0), Vec3(5.0, 5.0, 5.0)))
        bus.published.clear()

        // Move from zone-a into zone-b.
        listener.handle(moved(Vec3(5.0, 5.0, 5.0), Vec3(25.0, 5.0, 5.0)))

        assertThat(bus.publishedOfType<PlayerExitedRegion>().map { it.regionId })
            .containsExactly(RegionId("zone-a"))
        assertThat(bus.publishedOfType<PlayerEnteredRegion>().map { it.regionId })
            .containsExactly(RegionId("zone-b"))
    }

    @Test
    fun `overlapping regions track membership independently`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        index.register(world, RegionId("zone-overlap"), Region(Vec3(5.0, 0.0, 0.0), Vec3(15.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        // Step 1: enter zone-a only.
        listener.handle(moved(Vec3(-1.0, 5.0, 5.0), Vec3(1.0, 5.0, 5.0)))
        // Step 2: enter zone-overlap while still in zone-a.
        bus.published.clear()
        listener.handle(moved(Vec3(1.0, 5.0, 5.0), Vec3(7.0, 5.0, 5.0)))

        val entered = bus.publishedOfType<PlayerEnteredRegion>().map { it.regionId }
        assertThat(entered).containsExactly(RegionId("zone-overlap"))
        assertThat(bus.publishedOfType<PlayerExitedRegion>()).isEmpty()
    }

    @Test
    fun `forget drops the cache so next move re-derives baseline`() {
        val index = FakeStageRegionIndex()
        index.register(world, RegionId("zone-a"), Region(Vec3(0.0, 0.0, 0.0), Vec3(10.0, 10.0, 10.0)))
        val bus = FakeDomainEventBus()
        val listener = RegionTransitionDetectorListener(index, bus)

        listener.handle(moved(Vec3(-1.0, 5.0, 5.0), Vec3(5.0, 5.0, 5.0)))
        assertThat(listener.lastKnownRegions).containsKey(playerId)

        listener.forget(playerId)
        assertThat(listener.lastKnownRegions).doesNotContainKey(playerId)
    }
}
