package com.astarworks.astera.match.application.usecase.pin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryPlayerPinService
import com.astarworks.astera.match.domain.pin.PinSize
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PlacePinUseCaseTest {

    private val matchId = MatchId("m1")
    private val placer = PlayerId(UUID.randomUUID())

    @Test
    fun `place returns Success with default lifetime per size`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val uc = PlacePinUseCase(svc)

        val small = uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
        val medium = uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Medium)
        val large = uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Large)

        assertThat((small as Result.Success).value.lifetimeTicks).isEqualTo(200L)
        assertThat((medium as Result.Success).value.lifetimeTicks).isEqualTo(600L)
        assertThat((large as Result.Success).value.lifetimeTicks).isEqualTo(1200L)
    }

    @Test
    fun `place pushes the pin into the service so currentPins reflects it`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val uc = PlacePinUseCase(svc)

        uc.place(placer, matchId, Vec3(1.0, 64.0, 1.0), PinSize.Small)

        assertThat(svc.currentPins(matchId)).hasSize(1)
        assertThat(svc.currentPins(matchId).first().placerId).isEqualTo(placer)
    }

    @Test
    fun `place fails with TooManyPins when the player is at the cap`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val uc = PlacePinUseCase(svc)

        repeat(PlacePinUseCase.MAX_PINS_PER_PLAYER) {
            val r = uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
            assertThat(r.isSuccess).isTrue()
        }

        val rejected = uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
        val err = (rejected as Result.Failure).error
        assertThat(err).isInstanceOf(PinError.TooManyPins::class.java)
        assertThat((err as PinError.TooManyPins).currentCount)
            .isEqualTo(PlacePinUseCase.MAX_PINS_PER_PLAYER)
    }

    @Test
    fun `cap is per-player, not per-match`() {
        val svc = InMemoryPlayerPinService(FakeDomainEventBus())
        val uc = PlacePinUseCase(svc)
        val other = PlayerId(UUID.randomUUID())

        repeat(PlacePinUseCase.MAX_PINS_PER_PLAYER) {
            uc.place(placer, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
        }

        // Other player can still place in the same match.
        val r = uc.place(other, matchId, Vec3(0.0, 64.0, 0.0), PinSize.Small)
        assertThat(r.isSuccess).isTrue()
    }
}
