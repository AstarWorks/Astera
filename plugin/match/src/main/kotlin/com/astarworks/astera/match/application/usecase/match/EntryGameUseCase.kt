package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerEnteredMatch
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.usecase.spawn.MatchSpawnUseCase
import org.slf4j.LoggerFactory

/**
 * Registers [playerId] as a participant in [matchId].
 *
 * Entry is accepted only in WAITING or COUNTDOWN phases.
 * Rejects if the player is already registered.
 *
 * WP8: with `teamId == null` the player joins the **smallest** team (first on
 * ties) so admin/queue entries auto-balance, and, when a [spawn] use case is
 * wired, is teleported to a spawn of their own team on the stage. A teleport failure is logged but does
 * not undo the entry.
 */
public class EntryGameUseCase(
    private val matches: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val spawn: MatchSpawnUseCase? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

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

        val targetTeam = teamId ?: match.teams.minByOrNull { it.members.size }?.id
        val updated = match.addParticipant(playerId, targetTeam)
        matches.save(updated)
        eventBus.publish(PlayerEnteredMatch(matchId, playerId))

        if (spawn != null) {
            spawn.spawnPlayer(matchId, playerId).thenAccept { outcome -> if (outcome is Result.Failure) {
                log.warn("Entry teleport for {} into {} failed: {}", playerId, matchId, outcome.error)
            } }
        }

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
