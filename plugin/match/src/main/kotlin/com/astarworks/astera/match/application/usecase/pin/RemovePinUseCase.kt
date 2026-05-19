package com.astarworks.astera.match.application.usecase.pin

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.IPlayerPinService
import com.astarworks.astera.match.domain.pin.PlayerPinId

/**
 * Remove a [com.astarworks.astera.match.domain.pin.PlayerPin] (Wave 5e).
 *
 * Only the original placer may remove their pin; others receive
 * [PinError.NotOwner]. Missing pins return [PinError.NotFound] so callers
 * can distinguish "already expired" from "wrong player".
 *
 * Takes [MatchId] so the use case can look the pin up via the existing
 * [IPlayerPinService.currentPins] without requiring a global pin index on
 * the port. The Paper-side caller (Wave 6a) already knows the player's
 * current match.
 */
public class RemovePinUseCase(
    private val service: IPlayerPinService,
) {
    public fun remove(
        playerId: PlayerId,
        matchId: MatchId,
        pinId: PlayerPinId,
    ): Result<Unit, PinError> {
        val pin = service.currentPins(matchId).firstOrNull { it.id == pinId }
            ?: return Result.failure(PinError.NotFound(pinId))
        if (pin.placerId != playerId) {
            return Result.failure(PinError.NotOwner(playerId, pin.placerId, pinId))
        }
        service.expire(pinId)
        return Result.success(Unit)
    }
}

/**
 * Errors for the pin use cases. Shared by [PlacePinUseCase] and
 * [RemovePinUseCase] so caller code can treat all pin failures as a
 * single sealed surface.
 */
public sealed class PinError {
    public data class TooManyPins(
        val playerId: PlayerId,
        val currentCount: Int,
    ) : PinError()

    public data class NotOwner(
        val attempted: PlayerId,
        val actual: PlayerId,
        val pinId: PlayerPinId,
    ) : PinError()

    public data class NotFound(val pinId: PlayerPinId) : PinError()
}
