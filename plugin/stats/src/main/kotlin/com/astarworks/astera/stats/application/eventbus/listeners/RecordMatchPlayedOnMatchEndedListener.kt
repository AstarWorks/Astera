package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchPlayedUseCase
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] increments `matchesPlayed` for every participant of every
 * team in the match. Resolves the participant list by looking the match up in
 * [IMatchRepository] (the event only carries [com.astarworks.astera.core.domain.match.MatchId]).
 *
 * silly-knuth §5: subscribes to in-process [com.astarworks.astera.core.application.port.outbound.IDomainEventBus],
 * delegates to a use case that publishes [com.astarworks.astera.stats.domain.event.StatsUpdated] on the same bus
 * (cross-plugin notification, not external fan-out).
 *
 * Failure handling: per-player errors are logged at debug and swallowed —
 * recording one player's participation must not block the rest of the team.
 */
public class RecordMatchPlayedOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val recordMatchPlayed: RecordMatchPlayedUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug("RecordMatchPlayedOnMatchEndedListener: match {} not found", event.matchId)
            return
        }
        for (team in match.teams) {
            for (playerId in team.members) {
                runCatching { recordMatchPlayed.execute(playerId) }
                    .onFailure { err ->
                        log.debug(
                            "RecordMatchPlayedOnMatchEndedListener: failed for player={} match={} err={}",
                            playerId, event.matchId, err.message,
                        )
                    }
            }
        }
    }
}
