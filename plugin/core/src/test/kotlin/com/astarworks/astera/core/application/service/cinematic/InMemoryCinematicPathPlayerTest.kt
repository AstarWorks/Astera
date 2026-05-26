package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.cinematic.CameraTarget
import com.astarworks.astera.core.domain.cinematic.CameraWaypoint
import com.astarworks.astera.core.domain.cinematic.CinematicPath
import com.astarworks.astera.core.domain.event.CinematicEnded
import com.astarworks.astera.core.domain.event.CinematicStarted
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryCinematicPathPlayerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun samplePath(): CinematicPath = CinematicPath(
        listOf(
            CameraWaypoint(CameraTarget.Position(Vec3(0.0, 0.0, 0.0)), 20L, Easing.LINEAR),
            CameraWaypoint(CameraTarget.Position(Vec3(1.0, 0.0, 0.0)), 40L, Easing.EASE_IN_OUT_CUBIC),
        ),
    )

    @Test
    fun `play publishes CinematicStarted with correct duration`() {
        val bus = InMemoryDomainEventBus()
        val started = mutableListOf<CinematicStarted>()
        bus.subscribe(CinematicStarted::class) { started.add(it) }
        val player = InMemoryCinematicPathPlayer(bus, tickClock = { 50L })

        val r = player.play(playerId, samplePath())
        assertThat(r.isSuccess).isTrue()
        assertThat(started).hasSize(1)
        assertThat(started[0].playerId).isEqualTo(playerId)
        assertThat(started[0].totalDurationTicks).isEqualTo(20L)
        assertThat(started[0].atTick).isEqualTo(50L)
    }

    @Test
    fun `cancel publishes CinematicEnded with cancelled reason`() {
        val bus = InMemoryDomainEventBus()
        val ended = mutableListOf<CinematicEnded>()
        bus.subscribe(CinematicEnded::class) { ended.add(it) }
        val player = InMemoryCinematicPathPlayer(bus, tickClock = { 75L })

        val handle = (player.play(playerId, samplePath()) as Result.Success).value
        player.cancel(handle)

        assertThat(ended).hasSize(1)
        assertThat(ended[0].reasonKind).isEqualTo("cancelled")
        assertThat(ended[0].handle).isEqualTo(handle)
        assertThat(ended[0].atTick).isEqualTo(75L)
    }

    @Test
    fun `cancel on unknown handle is idempotent and does not publish`() {
        val bus = InMemoryDomainEventBus()
        val ended = mutableListOf<CinematicEnded>()
        bus.subscribe(CinematicEnded::class) { ended.add(it) }
        val player = InMemoryCinematicPathPlayer(bus)

        val handle = (player.play(playerId, samplePath()) as Result.Success).value
        player.cancel(handle)
        player.cancel(handle) // second time
        assertThat(ended).hasSize(1)
    }

    @Test
    fun `active reflects currently-playing handles`() {
        val bus = InMemoryDomainEventBus()
        val player = InMemoryCinematicPathPlayer(bus)

        val h1 = (player.play(playerId, samplePath()) as Result.Success).value
        val h2 = (player.play(playerId, samplePath()) as Result.Success).value
        assertThat(player.active()).containsExactlyInAnyOrder(h1, h2)
        player.cancel(h1)
        assertThat(player.active()).containsExactly(h2)
    }
}
