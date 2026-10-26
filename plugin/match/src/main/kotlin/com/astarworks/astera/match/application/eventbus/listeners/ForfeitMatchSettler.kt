package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.event.PlayerForfeited
import org.slf4j.LoggerFactory

/**
 * A match goes on without a forfeiter, unless that was the last member of their team (MODE-14):
 * the one team still standing wins ([EndReason.TeamVictory]); when nobody is left the match is cancelled.
 * Reads the roster after the forfeit removed the player, so a team that merely lost one of several
 * members is untouched. PvE parties and other single-sided matches never end on a member's forfeit
 * as long as a member remains.
 */
public class ForfeitMatchSettler(
    private val matches: IMatchRepository,
    private val end: (MatchId, EndReason) -> Unit,
) {
    private val log = LoggerFactory.getLogger(ForfeitMatchSettler::class.java)

    public fun onForfeited(event: PlayerForfeited) {
        val leaverTeam = event.teamId ?: return
        val match = matches.findById(event.matchId) ?: return
        if (match.phase != MatchPhase.ACTIVE && match.phase != MatchPhase.SUDDEN_DEATH) return
        if (match.teams.firstOrNull { it.id == leaverTeam }?.members?.isNotEmpty() != false) return

        val standing = match.teams.filter { it.id != leaverTeam && it.members.isNotEmpty() }
        when (standing.size) {
            0 -> {
                log.info("Match {}: everyone left; cancelling.", event.matchId)
                end(event.matchId, EndReason.Cancelled("all-players-left"))
            }
            1 -> {
                log.info("Match {}: team {} is empty after the forfeit; {} wins.", event.matchId, leaverTeam, standing.single().id)
                end(event.matchId, EndReason.TeamVictory(standing.single().id))
            }
            else -> Unit // free-for-all style matches with several teams left play on
        }
    }
}
