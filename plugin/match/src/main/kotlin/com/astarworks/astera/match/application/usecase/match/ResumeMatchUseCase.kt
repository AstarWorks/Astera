package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch

/**
 * Restore a [SessionState.Disconnected] participant back to
 * [SessionState.InMatch] and teleport them to the match's spectator/match
 * spawn (Wave 5h).
 *
 * Driven by `ReconnectDuringMatchListener` on `PlayerJoinedServer`. The
 * listener already checks the session is Disconnected before calling in;
 * this use case verifies again so it remains a safe public entry point
 * (idempotency / direct admin commands).
 *
 * The match must still be in a phase that accepts reconnections — anything
 * other than [MatchPhase.ACTIVE] returns [ResumeError.PhaseNotResumable]
 * (COUNTDOWN/SUDDEN_DEATH/ENDED all fall through to the lobby fallback the
 * listener applies on failure).
 *
 * Publishes [PlayerReconnectedToMatch] on success so HUD / audit listeners
 * can react.
 */
public class ResumeMatchUseCase(
    private val sessions: IPlayerSessionRepository,
    private val matches: IMatchRepository,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun resume(playerId: PlayerId, matchId: MatchId): Result<Unit, ResumeError> {
        val session = sessions.find(playerId)
            ?: return Result.failure(ResumeError.SessionNotFound(playerId))

        if (session.state !is SessionState.Disconnected) {
            return Result.failure(ResumeError.NotDisconnected(playerId))
        }

        if (session.currentMatch != matchId) {
            return Result.failure(ResumeError.MatchMismatch(playerId, matchId))
        }

        val match = matches.findById(matchId)
            ?: return Result.failure(ResumeError.MatchNotFound(matchId))

        if (match.phase != MatchPhase.ACTIVE) {
            return Result.failure(ResumeError.PhaseNotResumable(match.phase))
        }

        // Transition first, then teleport. If the teleport fails the player is
        // already conceptually back in-match; the Paper adapter will retry on
        // the next tick (Wave 6b).
        sessions.upsert(
            session.copy(state = SessionState.InMatch, currentMatch = matchId),
        )

        // Use the spectator-spawn API as the "drop-in" location. Phase 6a will
        // introduce a dedicated match-spawn API if the spectator vantage proves
        // unsuitable; for Wave 5h this avoids inventing a second port for what
        // is effectively the same "put the player at the arena" operation.
        worldGateway.spectatorSpawn(matchId, playerId).onFailure { gatewayErr ->
            return Result.failure(ResumeError.TeleportFailed(gatewayErr.toString()))
        }

        eventBus.publish(
            PlayerReconnectedToMatch(
                playerId = playerId,
                matchId = matchId,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(Unit)
    }
}

/** Expected failure modes of [ResumeMatchUseCase.resume]. */
public sealed class ResumeError {
    public abstract val messageKey: MessageKey

    public data class SessionNotFound(val playerId: PlayerId) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.session-not-found")
    }

    public data class NotDisconnected(val playerId: PlayerId) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.not-disconnected")
    }

    public data class MatchMismatch(val playerId: PlayerId, val matchId: MatchId) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.match-mismatch")
    }

    public data class MatchNotFound(val matchId: MatchId) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.match-not-found")
    }

    public data class PhaseNotResumable(val phase: MatchPhase) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.phase-not-resumable")
    }

    public data class TeleportFailed(val detail: String) : ResumeError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.resume.error.teleport-failed")
    }
}
