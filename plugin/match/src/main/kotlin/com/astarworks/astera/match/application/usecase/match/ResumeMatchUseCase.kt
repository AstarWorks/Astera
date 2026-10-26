package com.astarworks.astera.match.application.usecase.match

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ISpawnProtectionGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import com.astarworks.astera.match.domain.event.PlayerReconnectedToMatch

/**
 * Restore a [SessionState.Disconnected] participant back to
 * [SessionState.InMatch] and teleport them to the match's spectator/match
 * spawn (Wave 5h).
 *
 * Driven by `ReconnectDuringMatchListener` on `PlayerArrivedOnServer`. The
 * listener already checks the session is Disconnected before calling in;
 * this use case verifies again so it remains a safe public entry point
 * (idempotency / direct admin commands).
 *
 * The match must still be running — anything other than
 * [MatchPhase.ACTIVE] / [MatchPhase.SUDDEN_DEATH] returns
 * [ResumeError.PhaseNotResumable] (COUNTDOWN/ENDED fall through to the lobby
 * fallback the listener applies on failure).
 *
 * With a [spawn] use case the player is put back at a spawn of their own team
 * (not the lobby-side spectator stub), gets [protectionSeconds] of spawn
 * protection and [afterResume] runs (loaner kit re-arm, HUD refresh).
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
    private val spawn: MatchSpawnUseCase? = null,
    private val protection: ISpawnProtectionGateway? = null,
    private val protectionSeconds: (MatchId) -> Int = { 0 },
    private val afterResume: (PlayerId) -> Unit = {},
) {

    public fun resume(playerId: PlayerId, matchId: MatchId): CompletableFuture<Result<Unit, ResumeError>> {
        val session = sessions.find(playerId)
            ?: return CompletableFuture.completedFuture(Result.failure(ResumeError.SessionNotFound(playerId)))

        if (session.state !is SessionState.Disconnected) {
            return CompletableFuture.completedFuture(Result.failure(ResumeError.NotDisconnected(playerId)))
        }

        if (session.currentMatch != matchId) {
            return CompletableFuture.completedFuture(Result.failure(ResumeError.MatchMismatch(playerId, matchId)))
        }

        val match = matches.findById(matchId)
            ?: return CompletableFuture.completedFuture(Result.failure(ResumeError.MatchNotFound(matchId)))

        if (match.phase != MatchPhase.ACTIVE && match.phase != MatchPhase.SUDDEN_DEATH) {
            return CompletableFuture.completedFuture(Result.failure(ResumeError.PhaseNotResumable(match.phase)))
        }

        // Transition first, then teleport. If the teleport fails the player is
        // already conceptually back in-match; the Paper adapter will retry on
        // the next tick (Wave 6b).
        sessions.upsert(
            session.copy(state = SessionState.InMatch, currentMatch = matchId),
        )

        val placed = if (spawn != null) {
            spawn.spawnPlayer(matchId, playerId).thenApply { result ->
                when (result) {
                    is Result.Success -> Result.success(Unit)
                    is Result.Failure -> Result.failure(ResumeError.TeleportFailed(result.error.toString()))
                }
            }
        } else {
            worldGateway.spectatorSpawn(matchId, playerId).thenApply { outcome -> outcome.mapError { ResumeError.TeleportFailed(it.toString()) } }
        }
        return placed.thenApply { outcome ->
            if (outcome is Result.Failure) return@thenApply outcome
            protection?.grantProtection(playerId, protectionSeconds(matchId))
            afterResume(playerId)

            eventBus.publish(
                PlayerReconnectedToMatch(
                    playerId = playerId,
                    matchId = matchId,
                    atTick = scheduler.currentTick(),
                ),
            )

            return@thenApply Result.success(Unit)
        }
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
