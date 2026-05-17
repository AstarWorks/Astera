package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Registers [playerId] as a participant in [matchId].
 *
 * Entry is accepted only in WAITING or COUNTDOWN phases.
 * Rejects if the player is already registered.
 */
public class EntryGameUseCase(
    private val matches: IMatchRepository,
    @Suppress("UnusedPrivateProperty")
    private val eventBus: IDomainEventBus,
) {

    public fun execute(
        matchId: MatchId,
        playerId: PlayerId,
        teamId: TeamId?,
    ): Result<Unit, EntryGameError> {
        val match = matches.findById(matchId)
            ?: return Result.failure(EntryGameError.MatchNotFound(matchId))

        if (match.phase != MatchPhase.WAITING && match.phase != MatchPhase.COUNTDOWN) {
            return Result.failure(EntryGameError.EntryClosed(matchId, match.phase))
        }

        if (match.hasParticipant(playerId)) {
            return Result.failure(EntryGameError.AlreadyRegistered(matchId, playerId))
        }

        val updated = match.addParticipant(playerId, teamId)
        matches.save(updated)

        return Result.success(Unit)
    }
}

/** Expected failure modes of [EntryGameUseCase.execute]. */
public sealed class EntryGameError {
    public abstract val messageKey: MessageKey

    public data class MatchNotFound(val matchId: MatchId) : EntryGameError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.match-not-found")
    }

    public data class EntryClosed(val matchId: MatchId, val phase: MatchPhase) : EntryGameError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.entry-closed")
    }

    public data class AlreadyRegistered(
        val matchId: MatchId,
        val playerId: PlayerId,
    ) : EntryGameError() {
        override val messageKey: MessageKey = MessageKey("astera.match.error.already-registered")
    }
}
