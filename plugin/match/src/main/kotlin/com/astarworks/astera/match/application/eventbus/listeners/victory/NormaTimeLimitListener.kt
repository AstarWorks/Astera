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
 * The Norma layer's time limit (MODE-15): a standard part of every game mode that has the layer on.
 *
 * Norma levels never end a match by themselves. This listener ends a match that ran [timeLimitTicks] of
 * ACTIVE time (`time_limit_seconds`; `0` or less = no limit, the mode has its own clock) through [IMatchLifecycle.end],
 * like every other mode (result screen, rewards, return to the lobby).
 *
 * Time-limit ranking, best first: the mode's own objective standing ([objectiveOf], skipped when the mode has none),
 * then the most stars carried, then the most kills (the Norma level is power progression, never a ranking); a full tie is a draw.
 *
 * Only matches for which [isNorma] holds are touched.
 */
public class NormaTimeLimitListener(
    private val matches: IMatchRepository,
    private val lifecycle: IMatchLifecycle,
    private val isNorma: (MatchId) -> Boolean,
    private val timeLimitTicks: (MatchId) -> Long,
    private val now: () -> Long,
    private val objectiveOf: (Match, TeamId) -> Int? = { _, _ -> null },
    private val starsOf: (Match, TeamId) -> Int = { _, _ -> 0 },
    private val killsOf: (Match, TeamId) -> Int = { match, team -> match.score.valueFor(team) },
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
            val limit = timeLimitTicks(match.id)
            if (limit <= 0L || current - since < limit) continue
            end(match.id, EndReason.NormaTimeLimit(leader(match)), "time limit")
        }
    }

    /** Time-limit winner: mode objective, then stars carried, then kills; `null` when all tie. */
    internal fun leader(match: Match): TeamId? {
        fun rank(team: TeamId): List<Int> =
            listOf(objectiveOf(match, team) ?: 0, starsOf(match, team), killsOf(match, team))
        val ranks = match.teams.associate { it.id to rank(it.id) }
        val best = ranks.values.maxWithOrNull(compareBy<List<Int>>({ it[0] }, { it[1] }, { it[2] })) ?: return null
        return ranks.entries.filter { it.value == best }.singleOrNull()?.key
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
