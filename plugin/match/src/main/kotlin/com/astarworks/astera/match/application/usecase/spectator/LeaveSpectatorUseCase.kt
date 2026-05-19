package com.astarworks.astera.match.application.usecase.spectator

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState

/**
 * Reverse of [EnterSpectatorUseCase] — transition a player out of
 * [SessionState.Spectator] back to [SessionState.Lobby] and clear
 * `currentMatch` (Wave 5e).
 *
 * # Sequence
 * 1. Look up the current [com.astarworks.astera.core.domain.player.PlayerSession]
 *    ([SpectatorError.SessionNotFound] if not tracked).
 * 2. If the current state is **not** [SessionState.Spectator], return
 *    [SpectatorError.NotSpectator] (idempotency / invariant guard).
 * 3. Otherwise upsert with `state = Lobby` and `currentMatch = null`.
 *
 * # Deferred to Wave 6
 * The actual teleport back to the lobby spawn is deferred until the lobby
 * world-gateway API lands. For Wave 5e this only updates session state.
 */
public class LeaveSpectatorUseCase(
    private val sessionRepository: IPlayerSessionRepository,
    // TODO(Wave 6): inject IWorldGateway + teleport back to lobby spawn.
) {

    public fun leave(playerId: PlayerId): Result<Unit, SpectatorError> {
        val current = sessionRepository.find(playerId)
            ?: return Result.failure(SpectatorError.SessionNotFound(playerId))

        if (current.state !is SessionState.Spectator) {
            return Result.failure(SpectatorError.NotSpectator(playerId))
        }

        sessionRepository.upsert(
            current.copy(state = SessionState.Lobby, currentMatch = null),
        )

        return Result.success(Unit)
    }
}
