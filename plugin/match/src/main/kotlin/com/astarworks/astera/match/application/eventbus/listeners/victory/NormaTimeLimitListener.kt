package com.astarworks.astera.match.application.eventbus.listeners.victory

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
 * Norma time limit.
 *
 * A Norma match is won by reaching the top Norma level (`NormaLevelService`, [EndReason.NormaLevelReached]); there is
 * no kill-count victory (`NormaLevelService` also counts the kills into [Match.score]). This listener ends a match that ran [timeLimitTicks] of ACTIVE time
 * through [IMatchLifecycle.end], like every other mode (result screen, rewards, return to the lobby). The winner on
 * time is the team with the highest Norma level, then the most stars carried, then the most kills; a full tie is a draw.
 *
 * Only matches for which [isNorma] holds are touched (other modes score for themselves).
 */
public class NormaTimeLimitListener(
    private val matches: IMatchRepository,
    private val lifecycle: IMatchLifecycle,
    private val isNorma: (MatchId) -> Boolean,
    private val timeLimitTicks: (MatchId) -> Long,
    private val now: () -> Long,
    private val levelOf: (Match, TeamId) -> Int = { _, _ -> 1 },
    private val starsOf: (Match, TeamId) -> Int = { _, _ -> 0 },
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

    /** Time-limit winner: highest Norma level, then most stars carried, then most kills; `null` when all tie. */
    internal fun leader(match: Match): TeamId? {
        fun rank(team: TeamId): Triple<Int, Int, Int> =
            Triple(levelOf(match, team), starsOf(match, team), match.score.valueFor(team))
        val best = match.teams.map { rank(it.id) }
            .maxWithOrNull(compareBy({ it.first }, { it.second }, { it.third })) ?: return null
        return match.teams.filter { rank(it.id) == best }.singleOrNull()?.id
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
