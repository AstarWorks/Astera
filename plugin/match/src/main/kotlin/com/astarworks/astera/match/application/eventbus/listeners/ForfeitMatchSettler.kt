package com.astarworks.astera.match.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
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
    /** Cancels a match that is still in COUNTDOWN because a side ran out of players (tells the stayers, ends it). */
    private val cancelCountdown: (MatchId) -> Unit = { id -> end(id, EndReason.Cancelled("opponents-left")) },
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

    /**
     * A participant left a match that has not started yet ([leaverTeam] = the team they were on). The match starts
     * with whoever is left, unless nobody is or a side is now empty: then there is nothing to play, it is cancelled
     * at once (everyone still in it goes back to the lobby) instead of starting with bots or ghosts only.
     */
    public fun onLeftCountdown(matchId: MatchId, leaverTeam: TeamId?) {
        val match = matches.findById(matchId) ?: return
        if (match.phase != MatchPhase.COUNTDOWN) return
        val occupied = match.teams.count { it.members.isNotEmpty() }
        val sideEmptied = leaverTeam != null && match.teams.firstOrNull { it.id == leaverTeam }?.members?.isEmpty() == true
        if (occupied == 0 || (sideEmptied && occupied == 1)) {
            log.info("Match {}: a side is empty before the start; cancelling.", matchId)
            cancelCountdown(matchId)
        }
    }
}
