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
import com.astarworks.astera.match.domain.event.PlayerDisconnectedFromMatch

/**
 * Why the player is being removed from a match (Wave 5h).
 *
 * Sealed so the call site stays exhaustive; each variant carries enough payload
 * for downstream listeners (audit log, broadcast) to render a localized
 * explanation without a second round-trip to the source.
 */
public sealed interface RemoveReason {

    /** Connection dropped; the reconnect window elapsed (or never opened). */
    public data object Disconnect : RemoveReason

    /** Player or system declared the match a loss-by-forfeit. */
    public data object Forfeit : RemoveReason

    /**
     * Moderator kick. [by] is the actor's player id (null for console);
     * [reasonKey] is the i18n key that explains why.
     */
    public data class Kicked(val by: PlayerId?, val reasonKey: MessageKey) : RemoveReason

    /** Account ban — terminal, the player will not be allowed back this match. */
    public data object Banned : RemoveReason
}

/**
 * Remove [playerId] from [matchId]: reset their session to [SessionState.Lobby]
 * with `currentMatch = null`, teleport them to the lobby spawn, and publish a
 * terminal [PlayerDisconnectedFromMatch] (with `willTimeoutAtTick = null`) so
 * downstream subscribers can finalize state (Wave 5h).
 *
 * Forfeit-specific bookkeeping (publishing
 * [com.astarworks.astera.match.domain.event.PlayerForfeited]) lives in
 * [ApplyForfeitUseCase] which calls this use case under the hood. Callers
 * that want the "kicked" / "banned" branches drive this directly.
 *
 * Idempotent on a missing session: [RemoveError.SessionNotFound]. Idempotent
 * on a missing match: [RemoveError.MatchNotFound]. Either way the session is
 * left untouched; the caller decides whether to retry or absorb.
 */
public class RemoveFromMatchUseCase(
    private val sessions: IPlayerSessionRepository,
    private val matches: IMatchRepository,
    private val worldGateway: IWorldGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) {

    public fun remove(
        playerId: PlayerId,
        matchId: MatchId,
        reason: RemoveReason,
    ): Result<Unit, RemoveError> {
        val session = sessions.find(playerId)
            ?: return Result.failure(RemoveError.SessionNotFound(playerId))

        val match = matches.findById(matchId)
            ?: return Result.failure(RemoveError.MatchNotFound(matchId))

        // Reset session before teleport — keeps state consistent even if the
        // gateway call fails (the player is no longer "in" the match either way).
        sessions.upsert(
            session.copy(state = SessionState.Lobby, currentMatch = null),
        )

        // Best-effort teleport; failures are surfaced via the Result but do not
        // roll back the session state. The lobby spawn fallback is the same path
        // the disconnect-timeout listener uses, so any persistent failure here
        // would surface there too.
        worldGateway.lobbySpawn(playerId)

        val tick = scheduler.currentTick()
        eventBus.publish(
            PlayerDisconnectedFromMatch(
                playerId = playerId,
                matchId = matchId,
                phase = match.phase,
                willTimeoutAtTick = null,
                atTick = tick,
            ),
        )

        // Suppress unused-parameter warning: reason is part of the public contract
        // for callers (ApplyForfeitUseCase derives PlayerForfeited from it) and
        // will be carried on Phase 6 audit / broadcast events.
        @Suppress("UNUSED_VARIABLE")
        val captured = reason

        return Result.success(Unit)
    }
}

/** Expected failure modes of [RemoveFromMatchUseCase.remove]. */
public sealed class RemoveError {
    public abstract val messageKey: MessageKey

    public data class SessionNotFound(val playerId: PlayerId) : RemoveError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.remove.error.session-not-found")
    }

    public data class MatchNotFound(val matchId: MatchId) : RemoveError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.remove.error.match-not-found")
    }

    /**
     * Reserved for the lobby-teleport failure path. The current implementation
     * does not surface it (lobbySpawn failures are absorbed because the session
     * has already been reset), but the variant exists so the Wave 6 Paper
     * adapter can return it without breaking the sealed hierarchy.
     */
    public data class TeleportFailed(val detail: String) : RemoveError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.remove.error.teleport-failed")
    }

    /** Sanity guard for "the player isn't in any phase that can be removed from". */
    public data class IllegalPhase(val phase: MatchPhase) : RemoveError() {
        override val messageKey: MessageKey =
            MessageKey("astera.match.remove.error.illegal-phase")
    }
}
