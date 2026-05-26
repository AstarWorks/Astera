package com.astarworks.astera.core.application.service.world

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.event.WeatherChanged
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.region.RegionId
import com.astarworks.astera.core.domain.world.WeatherState
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryRegionEventFilterTest {

    private val player = PlayerId(UUID.randomUUID())
    private val arena = WorldId("arena-1")
    private val region = RegionId("spawn-no-pvp")

    @Test
    fun `subscribe delivers events whose position falls inside region`() {
        val bus = InMemoryDomainEventBus()
        val filter = InMemoryRegionEventFilter(
            eventBus = bus,
            regionMembership = { _, _, pos -> pos.x in 0.0..10.0 },
        )
        var received = 0
        filter.subscribe(region, PlayerMoved::class) { received++ }
        // inside
        bus.publish(PlayerMoved(player, Vec3.ZERO, Vec3(5.0, 64.0, 0.0), arena, atTick = 1L))
        // outside
        bus.publish(PlayerMoved(player, Vec3.ZERO, Vec3(20.0, 64.0, 0.0), arena, atTick = 2L))
        assertThat(received).isEqualTo(1)
    }

    @Test
    fun `subscribe drops events without spatial payload`() {
        val bus = InMemoryDomainEventBus()
        val filter = InMemoryRegionEventFilter(
            eventBus = bus,
            regionMembership = { _, _, _ -> true },
        )
        var received = 0
        filter.subscribe(region, WeatherChanged::class) { received++ }
        bus.publish(
            WeatherChanged(arena, WeatherState.Clear, WeatherState.Rain(6000), atTick = 1L),
        )
        // Default extractor returns null for WeatherChanged so handler is never invoked.
        assertThat(received).isEqualTo(0)
    }

    @Test
    fun `cancel unsubscribes the inner bus entry`() {
        val bus = InMemoryDomainEventBus()
        val filter = InMemoryRegionEventFilter(
            eventBus = bus,
            regionMembership = { _, _, _ -> true },
        )
        var received = 0
        val sub = filter.subscribe(region, PlayerMoved::class) { received++ }
        bus.publish(PlayerMoved(player, Vec3.ZERO, Vec3(5.0, 64.0, 0.0), arena, atTick = 1L))
        sub.cancel()
        bus.publish(PlayerMoved(player, Vec3.ZERO, Vec3(5.0, 64.0, 0.0), arena, atTick = 2L))
        assertThat(received).isEqualTo(1)
        assertThat(sub.isActive).isFalse
    }

    @Test
    fun `default membership predicate rejects all`() {
        val bus = InMemoryDomainEventBus()
        val filter = InMemoryRegionEventFilter(eventBus = bus)
        var received = 0
        filter.subscribe(region, PlayerMoved::class) { received++ }
        bus.publish(PlayerMoved(player, Vec3.ZERO, Vec3(5.0, 64.0, 0.0), arena, atTick = 1L))
        assertThat(received).isEqualTo(0)
    }
}
