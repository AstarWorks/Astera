package com.astarworks.astera.match.application.usecase.spectator

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.domain.event.PlayerEnteredSpectator

/**
 * Transition a tracked player into [SessionState.Spectator] for a given match
 * (Wave 5e → wired to [IWorldGateway.spectatorSpawn] in Wave 5h).
 *
 * # Sequence
 * 1. Look up the existing [PlayerSession] via [IPlayerSessionRepository.find]
 *    ([SpectatorError.SessionNotFound] if no session is tracked — e.g. the
 *    player has never joined or was already cleaned up).
 * 2. If the current state is already [SessionState.Spectator], short-circuit
 *    with [SpectatorError.AlreadySpectator] (idempotency guard).
 * 3. Otherwise [IPlayerSessionRepository.upsert] a new session with
 *    `state = Spectator` and `currentMatch = matchId`.
 * 4. If [worldGateway] is non-null, call [IWorldGateway.spectatorSpawn] to
 *    teleport the player to the match's spectator vantage point. Gateway
 *    failures surface as [SpectatorError.TeleportFailed] but the session
 *    transition is *not* rolled back — the player is conceptually a spectator
 *    either way; the platform layer (Wave 6b) retries the teleport.
 *
 * [worldGateway] is nullable so legacy tests can wire the use case without
 * the gateway. Production wiring (`AsteraMatchPlugin`) supplies the real impl.
 */
public class EnterSpectatorUseCase(
    private val sessionRepository: IPlayerSessionRepository,
    private val worldGateway: IWorldGateway? = null,
    private val eventBus: IDomainEventBus? = null,
    private val scheduler: IScheduler? = null,
) {

    public fun enter(playerId: PlayerId, matchId: MatchId): CompletableFuture<Result<Unit, SpectatorError>> {
        val current = sessionRepository.find(playerId)
            ?: return CompletableFuture.completedFuture(Result.failure(SpectatorError.SessionNotFound(playerId)))

        if (current.state is SessionState.Spectator) {
            return CompletableFuture.completedFuture(Result.failure(SpectatorError.AlreadySpectator(playerId)))
        }

        sessionRepository.upsert(
            current.copy(state = SessionState.Spectator, currentMatch = matchId),
        )

        val placed = worldGateway?.spectatorSpawn(matchId, playerId)
            ?: CompletableFuture.completedFuture(Result.success(Unit))
        return placed.thenApply { outcome ->
            if (outcome is Result.Failure) return@thenApply Result.failure(SpectatorError.TeleportFailed(outcome.error.toString()))


            // Wave 5match-hud (ADR-0118) — publish PlayerEnteredSpectator so
            // SpectatorCameraControlListener (freelook gateway wire) + future
            // audit listeners can react. Optional event bus keeps existing
            // tests (which omit it) source-compatible.
            if (eventBus != null) {
                eventBus.publish(
                    PlayerEnteredSpectator(
                        playerId = playerId,
                        matchId = matchId,
                        atTick = scheduler?.currentTick() ?: 0L,
                    ),
                )
        }

        return@thenApply Result.success(Unit)
        }
    }
}

/**
 * Expected failure modes of the spectator-transition use cases
 * ([EnterSpectatorUseCase], [LeaveSpectatorUseCase]).
 *
 * Each variant carries an i18n [messageKey] so command / event handlers can
 * surface a localized message via `IMessageRenderer` without leaking domain
 * detail.
 */
public sealed class SpectatorError {
    public abstract val messageKey: MessageKey

    public data class SessionNotFound(val playerId: PlayerId) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.error.session-not-found")
    }

    public data class AlreadySpectator(val playerId: PlayerId) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.error.already-spectator")
    }

    public data class NotSpectator(val playerId: PlayerId) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.error.not-spectator")
    }

    public data class TeleportFailed(val reason: String) : SpectatorError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.spectator.error.teleport-failed")
    }
}
