package com.astarworks.astera.match.application.usecase.pin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IPlayerPinService
import com.astarworks.astera.match.domain.pin.PinSize
import com.astarworks.astera.match.domain.pin.PlayerPin
import com.astarworks.astera.match.domain.pin.PlayerPinId

/**
 * Place a [PlayerPin] for [placer] in [matchId] at [position] with [size]
 * (Wave 5e). Default lifetime depends on size — see
 * [PlacePinUseCase.lifetimeFor].
 *
 * Fails with [PinError.TooManyPins] if [placer] already has
 * [MAX_PINS_PER_PLAYER] pins in this match.
 */
public class PlacePinUseCase(
    private val service: IPlayerPinService,
) {
    public fun place(
        placer: PlayerId,
        matchId: MatchId,
        position: Vec3,
        size: PinSize,
    ): Result<PlayerPin, PinError> {
        val owned = service.currentPins(matchId).count { it.placerId == placer }
        if (owned >= MAX_PINS_PER_PLAYER) {
            return Result.failure(PinError.TooManyPins(placer, owned))
        }

        val pin = PlayerPin(
            id = PlayerPinId.random(),
            placerId = placer,
            matchId = matchId,
            position = position,
            size = size,
            lifetimeTicks = lifetimeFor(size),
        )
        service.place(pin)
        return Result.success(pin)
    }

    public companion object {
        /** Per-player cap; placing a sixth pin is rejected. */
        public const val MAX_PINS_PER_PLAYER: Int = 5

        /** Default lifetime per size (ticks; 20 TPS). */
        public fun lifetimeFor(size: PinSize): Long = when (size) {
            PinSize.Small -> 200L   // 10 s
            PinSize.Medium -> 600L  // 30 s
            PinSize.Large -> 1200L  // 60 s
        }
    }
}
