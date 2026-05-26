package com.astarworks.astera.core.application.service.cinematic

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.port.outbound.cinematic.CameraError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.cinematic.CameraTarget
import com.astarworks.astera.core.domain.event.CameraReleased
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryCameraGatewayTest {

    private val playerId = PlayerId(UUID.randomUUID())

    private fun newGateway(tickClock: () -> Long = { 100L }): Pair<InMemoryCameraGateway, MutableList<CameraReleased>> {
        val bus = InMemoryDomainEventBus()
        val captured = mutableListOf<CameraReleased>()
        bus.subscribe(CameraReleased::class) { captured.add(it) }
        return InMemoryCameraGateway(bus, tickClock) to captured
    }

    @Test
    fun `lockCamera returns handle and records it as active`() {
        val (g, _) = newGateway()
        val r = g.lockCamera(playerId, CameraTarget.Position(Vec3(1.0, 2.0, 3.0)))
        assertThat(r.isSuccess).isTrue()
        val handle = (r as Result.Success).value
        assertThat(g.activeHandle(playerId)).isEqualTo(handle)
    }

    @Test
    fun `lockCamera twice replaces handle without releasing the first`() {
        val (g, captured) = newGateway()
        val first = (g.lockCamera(playerId, CameraTarget.Position(Vec3(0.0, 0.0, 0.0))) as Result.Success).value
        val second = (g.lockCamera(playerId, CameraTarget.Position(Vec3(1.0, 0.0, 0.0))) as Result.Success).value
        assertThat(g.activeHandle(playerId)).isEqualTo(second)
        assertThat(g.activeHandle(playerId)).isNotEqualTo(first)
        assertThat(captured).isEmpty() // implicit replace does not fire CameraReleased
    }

    @Test
    fun `releaseCamera publishes CameraReleased and clears active`() {
        val (g, captured) = newGateway()
        g.lockCamera(playerId, CameraTarget.Position(Vec3(0.0, 0.0, 0.0)))
        g.releaseCamera(playerId)
        assertThat(g.activeHandle(playerId)).isNull()
        assertThat(captured).hasSize(1)
        assertThat(captured[0].playerId).isEqualTo(playerId)
        assertThat(captured[0].atTick).isEqualTo(100L)
    }

    @Test
    fun `releaseCamera on non-locked player is idempotent`() {
        val (g, captured) = newGateway()
        val r = g.releaseCamera(playerId)
        assertThat(r.isSuccess).isTrue()
        assertThat(captured).isEmpty()
    }

    @Test
    fun `panCamera rejects negative duration`() {
        val (g, _) = newGateway()
        val r = g.panCamera(
            playerId = playerId,
            fromTarget = CameraTarget.Position(Vec3.ZERO),
            toTarget = CameraTarget.Position(Vec3(1.0, 0.0, 0.0)),
            durationTicks = -1L,
            easing = Easing.LINEAR,
        )
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(CameraError.InvalidDuration::class.java)
    }

    @Test
    fun `shakeCamera rejects intensity above ceiling`() {
        val (g, _) = newGateway()
        val r = g.shakeCamera(playerId, intensity = 1.5, durationTicks = 20)
        assertThat(r.isFailure).isTrue()
        assertThat((r as Result.Failure).error).isInstanceOf(CameraError.InvalidIntensity::class.java)
    }

    @Test
    fun `zoomCamera rejects non-positive multiplier`() {
        val (g, _) = newGateway()
        val r1 = g.zoomCamera(playerId, fovMultiplier = 0.0, durationTicks = 20)
        val r2 = g.zoomCamera(playerId, fovMultiplier = -0.5, durationTicks = 20)
        assertThat(r1.isFailure).isTrue()
        assertThat(r2.isFailure).isTrue()
    }

    @Test
    fun `freeze rejects negative duration`() {
        val (g, _) = newGateway()
        val r = g.freeze(playerId, durationTicks = -1)
        assertThat(r.isFailure).isTrue()
    }

    @Test
    fun `freeze records active handle (auto-release is Paper adapter responsibility)`() {
        val (g, _) = newGateway()
        val r = g.freeze(playerId, durationTicks = 40)
        assertThat(r.isSuccess).isTrue()
        assertThat(g.activeHandle(playerId)).isNotNull()
    }
}
