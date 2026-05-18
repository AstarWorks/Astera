package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IRewardBridge
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.reward.RewardBundle
import com.astarworks.astera.core.domain.reward.RewardBundleId
import com.astarworks.astera.core.domain.event.MatchEnded
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * On [MatchEnded], enqueues a cross-server [RewardBundle] for each member of
 * the winning team. Wave 5e PoC uses fixed defaults (100 STAR + 50 XP);
 * Phase 4 will read these from match-config (reward tables per game-mode).
 *
 * Looks the match up in [IMatchRepository] to resolve team rosters — the
 * event only carries the [com.astarworks.astera.core.domain.match.MatchId]
 * and the winning [com.astarworks.astera.core.domain.match.TeamId].
 *
 * Draws ([MatchEnded.winningTeam] is null) produce no rewards (Phase 1 PoC).
 *
 * silly-knuth §5: in-process only — bundle consumption happens on the lobby
 * side via [IRewardBridge.consumeForPlayer] when the player reconnects.
 */
public class RewardOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val bridge: IRewardBridge,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val winningTeam = event.winningTeam
        if (winningTeam == null) {
            log.debug(
                "RewardOnMatchEndedListener: match {} ended as draw, no reward enqueued",
                event.matchId,
            )
            return
        }
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug(
                "RewardOnMatchEndedListener: match {} not found, no reward enqueued",
                event.matchId,
            )
            return
        }
        val team = match.teams.firstOrNull { it.id == winningTeam }
        if (team == null) {
            log.debug(
                "RewardOnMatchEndedListener: winning team {} not on match {}",
                winningTeam, event.matchId,
            )
            return
        }
        val now = clock.instant()
        for (playerId in team.members) {
            val bundle = RewardBundle(
                id = RewardBundleId.random(),
                playerId = playerId,
                items = emptyList(),
                currency = mapOf(Currency.STAR to MATCH_WIN_STAR),
                xp = MATCH_WIN_XP,
                createdAt = now,
            )
            bridge.enqueue(bundle)
        }
    }

    public companion object {
        public const val MATCH_WIN_STAR: Long = 100L
        public const val MATCH_WIN_XP: Long = 50L
    }
}
