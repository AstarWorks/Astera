package com.astarworks.astera.match.application.usecase.pin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryPlayerPinService
import com.astarworks.astera.match.domain.event.PlayerPinExpired
import com.astarworks.astera.match.domain.pin.PinSize
import com.astarworks.astera.match.domain.pin.PlayerPinId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RemovePinUseCaseTest {

    private val matchId = MatchId("m1")
    private val placer = PlayerId(UUID.randomUUID())
    private val intruder = PlayerId(UUID.randomUUID())

    @Test
    fun `remove succeeds when the placer asks and triggers PlayerPinExpired`() {
        val bus = FakeDomainEventBus()
        val svc = InMemoryPlayerPinService(bus)
        val place = PlacePinUseCase(svc)
        val remove = RemovePinUseCase(svc)
        val pin = (place.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
            as Result.Success).value

        val r = remove.remove(placer, matchId, pin.id)

        assertThat(r.isSuccess).isTrue()
        assertThat(svc.currentPins(matchId)).isEmpty()
        val expired = bus.publishedOfType<PlayerPinExpired>()
        assertThat(expired).hasSize(1)
        assertThat(expired.first().pinId).isEqualTo(pin.id)
    }

    @Test
    fun `remove fails with NotOwner when a non-placer asks`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val place = PlacePinUseCase(svc)
        val remove = RemovePinUseCase(svc)
        val pin = (place.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
            as Result.Success).value

        val r = remove.remove(intruder, matchId, pin.id)

        val err = (r as Result.Failure).error
        assertThat(err).isInstanceOf(PinError.NotOwner::class.java)
        assertThat((err as PinError.NotOwner).attempted).isEqualTo(intruder)
        assertThat(err.actual).isEqualTo(placer)
        // Pin must still be live.
        assertThat(svc.currentPins(matchId)).hasSize(1)
    }

    @Test
    fun `remove fails with NotFound when the pin does not exist`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val remove = RemovePinUseCase(svc)

        val r = remove.remove(placer, matchId, PlayerPinId.random())

        assertThat((r as Result.Failure).error).isInstanceOf(PinError.NotFound::class.java)
    }
}
