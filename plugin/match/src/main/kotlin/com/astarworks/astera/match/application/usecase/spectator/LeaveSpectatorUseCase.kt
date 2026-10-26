package com.astarworks.astera.match.application.usecase.spectator

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState

/**
 * Reverse of [EnterSpectatorUseCase] — transition a player out of
 * [SessionState.Spectator] back to [SessionState.Lobby] and clear
 * `currentMatch` (Wave 5e → wired to [IWorldGateway.lobbySpawn] in Wave 5h).
 *
 * # Sequence
 * 1. Look up the current [com.astarworks.astera.core.domain.player.PlayerSession]
 *    ([SpectatorError.SessionNotFound] if not tracked).
 * 2. If the current state is **not** [SessionState.Spectator], return
 *    [SpectatorError.NotSpectator] (idempotency / invariant guard).
 * 3. Otherwise upsert with `state = Lobby` and `currentMatch = null`.
 * 4. If [worldGateway] is non-null, call [IWorldGateway.lobbySpawn]. Gateway
 *    failures surface as [SpectatorError.TeleportFailed] but the session
 *    transition is *not* rolled back — the player is conceptually back in the
 *    lobby; Wave 6b's Paper adapter retries the teleport.
 *
 * [worldGateway] is nullable for legacy tests; production wiring supplies
 * the real impl.
 */
public class LeaveSpectatorUseCase(
    private val sessionRepository: IPlayerSessionRepository,
    private val worldGateway: IWorldGateway? = null,
) {

    public fun leave(playerId: PlayerId): CompletableFuture<Result<Unit, SpectatorError>> {
        val current = sessionRepository.find(playerId)
            ?: return CompletableFuture.completedFuture(Result.failure(SpectatorError.SessionNotFound(playerId)))

        if (current.state !is SessionState.Spectator) {
            return CompletableFuture.completedFuture(Result.failure(SpectatorError.NotSpectator(playerId)))
        }

        sessionRepository.upsert(
            current.copy(state = SessionState.Lobby, currentMatch = null),
        )

        val placed = worldGateway?.lobbySpawn(playerId)
            ?: CompletableFuture.completedFuture(Result.success(Unit))
        return placed.thenApply { outcome ->
            if (outcome is Result.Failure) return@thenApply Result.failure(SpectatorError.TeleportFailed(outcome.error.toString()))


            return@thenApply Result.success(Unit)
        }
    }
}
