package com.astarworks.astera.match.application.eventbus.listeners.victory

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.match.application.port.inbound.IMatchLifecycle
import com.astarworks.astera.match.domain.event.PhaseChanged
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * G1: gives a Norma match a score and a real end.
 *
 * The Wave 5c Norma pipeline only knew a score threshold that nothing ever raised (stars are picked up
 * but never counted), so a queue-formed Norma match was an endless free-for-all that only an admin could
 * close. Now every kill of an enemy raises the killer team's score by one; the first team to
 * [threshold] kills wins ([EndReason.NormaReached]), and a match that runs [timeLimitTicks] of ACTIVE time ends for the team in
 * front (a tie is a draw). Both end through [IMatchLifecycle.end], i.e. the same path as every other
 * mode: result screen, rewards, return to the lobby.
 *
 * Only matches for which [isNorma] holds are touched (other modes score for themselves).
 */
public class NormaKillScoringListener(
    private val matches: IMatchRepository,
    private val lifecycle: IMatchLifecycle,
    private val isNorma: (MatchId) -> Boolean,
    private val threshold: (MatchId) -> Int,
    private val timeLimitTicks: (MatchId) -> Long,
    private val now: () -> Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Tick at which each match turned ACTIVE (the time limit counts from there). */
    private val activeSince: MutableMap<MatchId, Long> = ConcurrentHashMap()

    public fun onPhaseChanged(event: PhaseChanged) {
        when (event.to) {
            MatchPhase.ACTIVE -> activeSince.putIfAbsent(event.matchId, now())
            MatchPhase.ENDED -> activeSince.remove(event.matchId)
            else -> Unit
        }
    }

    public fun onKilled(event: PlayerKilled) {
        val killer = event.killer ?: return
        val match = matches.findMatchOf(event.victim) ?: return
        if (!live(match) || !isNorma(match.id)) return
        val killerTeam = match.teams.firstOrNull { killer in it.members }?.id ?: return
        val victimTeam = match.teams.firstOrNull { event.victim in it.members }?.id ?: return
        if (killerTeam == victimTeam) return

        val scored = match.copy(score = match.score.withIncrement(killerTeam, 1))
        matches.save(scored)
        val points = scored.score.valueFor(killerTeam)
        val goal = threshold(match.id)
        if (points >= goal) end(match.id, EndReason.NormaReached(killerTeam, goal), "score $points")
    }

    /** Called about once a second: ends matches whose time limit ran out. */
    public fun tick() {
        val current = now()
        for (match in matches.findActiveMatches()) {
            if (match.phase != MatchPhase.ACTIVE || !isNorma(match.id)) continue
            val since = activeSince.getOrPut(match.id) { current }
            if (current - since < timeLimitTicks(match.id)) continue
            end(match.id, EndReason.NormaTimeLimit(leader(match)), "time limit")
        }
    }

    /** The team strictly ahead on points, or `null` (draw). */
    internal fun leader(match: Match): TeamId? {
        val best = match.teams.maxOfOrNull { match.score.valueFor(it.id) } ?: return null
        return match.teams.filter { match.score.valueFor(it.id) == best }.singleOrNull()?.id
    }

    private fun end(matchId: MatchId, reason: EndReason, why: String) {
        activeSince.remove(matchId)
        when (val result = lifecycle.end(matchId, reason)) {
            is Result.Success -> log.info("Norma match {} ended ({}; {}).", matchId, reason, why)
            is Result.Failure -> log.debug("Norma end for {} failed ({}); likely already ended.", matchId, result.error)
        }
    }

    private fun live(match: Match): Boolean = match.phase == MatchPhase.ACTIVE || match.phase == MatchPhase.SUDDEN_DEATH
}
