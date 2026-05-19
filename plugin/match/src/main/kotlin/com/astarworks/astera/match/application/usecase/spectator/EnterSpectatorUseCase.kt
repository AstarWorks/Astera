package com.astarworks.astera.match.application.usecase.spectator

import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState

/**
 * Transition a tracked player into [SessionState.Spectator] for a given match
 * (Wave 5e).
 *
 * # Sequence
 * 1. Look up the existing [PlayerSession] via [IPlayerSessionRepository.find]
 *    ([SpectatorError.SessionNotFound] if no session is tracked — e.g. the
 *    player has never joined or was already cleaned up).
 * 2. If the current state is already [SessionState.Spectator], short-circuit
 *    with [SpectatorError.AlreadySpectator] (idempotency guard).
 * 3. Otherwise [IPlayerSessionRepository.upsert] a new session with
 *    `state = Spectator` and `currentMatch = matchId`.
 *
 * # Deferred to Wave 6
 * The actual teleport to the stage's spectator spawn point is deferred until
 * `IWorldGateway` exposes a spectator-spawn API (Wave 6). For Wave 5e we only
 * update session state — [SpectatorError.TeleportFailed] is reserved so the
 * Wave 6 wiring can return it without breaking the sealed hierarchy.
 */
public class EnterSpectatorUseCase(
    private val sessionRepository: IPlayerSessionRepository,
    // TODO(Wave 6): inject IWorldGateway once it exposes a spectator-spawn API,
    //   then teleport the player to the stage's spectator point before returning
    //   Result.success. Returning SpectatorError.TeleportFailed on gateway false.
) {

    public fun enter(playerId: PlayerId, matchId: MatchId): Result<Unit, SpectatorError> {
        val current = sessionRepository.find(playerId)
            ?: return Result.failure(SpectatorError.SessionNotFound(playerId))

        if (current.state is SessionState.Spectator) {
            return Result.failure(SpectatorError.AlreadySpectator(playerId))
        }

        sessionRepository.upsert(
            current.copy(state = SessionState.Spectator, currentMatch = matchId),
        )

        return Result.success(Unit)
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
