package com.astarworks.astera.match.application.service.result

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-match, per-player kills / deaths / assists / objective counters for the
 * end-of-match result screen and the reward calculation.
 *
 * Only this match's numbers live here (the lifetime counters are the stats
 * plugin's). The wiring feeds it from the combat kill / assist events and the
 * Nexus-destroyed event; a counter is credited only while the match is live
 * and only between its own participants:
 *
 *  - a kill counts when killer and victim are in the same match and on
 *    different teams (a team kill or a self-kill is only a death);
 *  - an assist counts for a participant of the victim's match;
 *  - an objective is a Nexus destroyed by a participant of the match.
 *
 * [take] hands the counters over once, at match end, and forgets the match.
 */
public class MatchTallyService(private val matches: IMatchRepository) {

    /** Counters of one player. */
    public data class Counts(val kills: Int = 0, val deaths: Int = 0, val assists: Int = 0, val objective: Int = 0)

    private val tallies: MutableMap<MatchId, MutableMap<PlayerId, Counts>> = ConcurrentHashMap()
    private val modeKeys: MutableMap<MatchId, String> = ConcurrentHashMap()

    /** Remember the game mode a match runs (the registry forgets it when the match ends). */
    public fun onMatchStarted(matchId: MatchId, modeKey: String) {
        modeKeys[matchId] = modeKey
    }

    public fun modeKeyOf(matchId: MatchId): String = modeKeys[matchId].orEmpty()

    public fun onKill(victim: PlayerId, killer: PlayerId?) {
        val match = liveMatchOf(victim) ?: return
        bump(match.id, victim) { it.copy(deaths = it.deaths + 1) }
        if (killer == null || killer == victim) return
        val killerTeam = match.teams.firstOrNull { killer in it.members }?.id ?: return
        val victimTeam = match.teams.firstOrNull { victim in it.members }?.id
        if (killerTeam == victimTeam) return
        bump(match.id, killer) { it.copy(kills = it.kills + 1) }
    }

    /** A participant killed a mob (PvE, ADR-0249): counts as a kill; the mob has no team and no deaths. */
    public fun onMobKill(killer: PlayerId?) {
        if (killer == null) return
        val match = liveMatchOf(killer) ?: return
        bump(match.id, killer) { it.copy(kills = it.kills + 1) }
    }

    public fun onAssist(assistant: PlayerId, victim: PlayerId) {
        val match = liveMatchOf(victim) ?: return
        if (!match.hasParticipant(assistant) || assistant == victim) return
        bump(match.id, assistant) { it.copy(assists = it.assists + 1) }
    }

    public fun onObjective(matchId: MatchId, player: PlayerId?) {
        if (player == null) return
        val match = matches.findById(matchId) ?: return
        if (match.phase != MatchPhase.ACTIVE && match.phase != MatchPhase.SUDDEN_DEATH) return
        if (!match.hasParticipant(player)) return
        bump(matchId, player) { it.copy(objective = it.objective + 1) }
    }

    /** The counters of [matchId], removing them. A second call returns nothing. */
    public fun take(matchId: MatchId): Map<PlayerId, Counts> {
        modeKeys.remove(matchId)
        return tallies.remove(matchId)?.toMap().orEmpty()
    }

    private fun liveMatchOf(player: PlayerId) =
        matches.findMatchOf(player)?.takeIf { it.phase == MatchPhase.ACTIVE || it.phase == MatchPhase.SUDDEN_DEATH }

    private fun bump(matchId: MatchId, player: PlayerId, change: (Counts) -> Counts) {
        val perPlayer = tallies.computeIfAbsent(matchId) { ConcurrentHashMap() }
        perPlayer.merge(player, change(Counts()), { old, _ -> change(old) })
    }
}
