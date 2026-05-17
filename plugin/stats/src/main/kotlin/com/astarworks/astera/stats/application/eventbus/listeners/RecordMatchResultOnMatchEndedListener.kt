package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.match.application.port.outbound.IMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchResultUseCase
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] credits a win to every member of the winning team and a loss
 * to every member of every other team. A null [MatchEnded.winningTeam] is a
 * draw — no W/L is recorded (participation is still recorded by the sibling
 * [RecordMatchPlayedOnMatchEndedListener]).
 *
 * Looks the match up in [IMatchRepository] to resolve team rosters; the event
 * itself only carries [com.astarworks.astera.match.domain.match.MatchId] and the
 * winning [com.astarworks.astera.match.domain.match.TeamId].
 *
 * silly-knuth §5: in-process only, no broadcaster.
 */
public class RecordMatchResultOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val recordMatchResult: RecordMatchResultUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val winningTeam = event.winningTeam
        if (winningTeam == null) {
            log.debug("RecordMatchResultOnMatchEndedListener: match {} ended as draw, no W/L recorded", event.matchId)
            return
        }
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug("RecordMatchResultOnMatchEndedListener: match {} not found", event.matchId)
            return
        }
        for (team in match.teams) {
            val outcome = if (team.id == winningTeam) {
                RecordMatchResultUseCase.Outcome.WIN
            } else {
                RecordMatchResultUseCase.Outcome.LOSS
            }
            for (playerId in team.members) {
                runCatching { recordMatchResult.execute(playerId, outcome) }
                    .onFailure { err ->
                        log.debug(
                            "RecordMatchResultOnMatchEndedListener: failed for player={} match={} outcome={} err={}",
                            playerId, event.matchId, outcome, err.message,
                        )
                    }
            }
        }
    }
}
