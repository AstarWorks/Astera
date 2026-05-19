package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.event.PlayerPinExpired
import com.astarworks.astera.match.domain.event.PlayerPinPlaced
import com.astarworks.astera.match.domain.pin.PinSize
import com.astarworks.astera.match.domain.pin.PlayerPin
import com.astarworks.astera.match.domain.pin.PlayerPinId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerPinServiceTest {

    private val matchId = MatchId("m1")
    private val placer = PlayerId(UUID.randomUUID())

    private fun pin(lifetime: Long = 200L, id: PlayerPinId = PlayerPinId.random()): PlayerPin =
        PlayerPin(
            id = id,
            placerId = placer,
            matchId = matchId,
            position = Vec3(1.0, 64.0, 1.0),
            size = PinSize.Small,
            lifetimeTicks = lifetime,
        )

    @Test
    fun `place stores the pin and publishes PlayerPinPlaced`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val p = pin()

        svc.place(p)

        assertThat(svc.currentPins(matchId)).containsExactly(p)
        val placed = bus.publishedOfType<PlayerPinPlaced>()
        assertThat(placed).hasSize(1)
        assertThat(placed.first().pin).isEqualTo(p)
    }

    @Test
    fun `expire removes the pin and publishes PlayerPinExpired`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val p = pin()
        svc.place(p)

        svc.expire(p.id)

        assertThat(svc.currentPins(matchId)).isEmpty()
        val expired = bus.publishedOfType<PlayerPinExpired>()
        assertThat(expired).hasSize(1)
        assertThat(expired.first().pinId).isEqualTo(p.id)
    }

    @Test
    fun `expire on unknown id is a no-op`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)

        svc.expire(PlayerPinId.random())

        assertThat(bus.publishedOfType<PlayerPinExpired>()).isEmpty()
    }

    @Test
    fun `tick decrements remaining lifetime and auto-expires at zero`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val p = pin(lifetime = 100L)
        svc.place(p)

        svc.tick(matchId, 50L)
        assertThat(svc.currentPins(matchId)).containsExactly(p)
        assertThat(bus.publishedOfType<PlayerPinExpired>()).isEmpty()

        svc.tick(matchId, 50L)
        assertThat(svc.currentPins(matchId)).isEmpty()
        val expired = bus.publishedOfType<PlayerPinExpired>()
        assertThat(expired).hasSize(1)
        assertThat(expired.first().pinId).isEqualTo(p.id)
    }

    @Test
    fun `tick over-shoot also expires (remaining drops below zero)`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val p = pin(lifetime = 50L)
        svc.place(p)

        svc.tick(matchId, 1_000L)

        assertThat(svc.currentPins(matchId)).isEmpty()
        assertThat(bus.publishedOfType<PlayerPinExpired>()).hasSize(1)
    }

    @Test
    fun `tick with zero elapsed does nothing`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val p = pin(lifetime = 100L)
        svc.place(p)

        svc.tick(matchId, 0L)

        assertThat(svc.currentPins(matchId)).containsExactly(p)
        assertThat(bus.publishedOfType<PlayerPinExpired>()).isEmpty()
    }

    @Test
    fun `currentPins is scoped per match`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val other = MatchId("m2")
        svc.place(pin())
        svc.place(
            PlayerPin(
                id = PlayerPinId.random(),
                placerId = placer,
                matchId = other,
                position = Vec3.ZERO,
                size = PinSize.Large,
                lifetimeTicks = 200L,
            ),
        )

        assertThat(svc.currentPins(matchId)).hasSize(1)
        assertThat(svc.currentPins(other)).hasSize(1)
    }
}
