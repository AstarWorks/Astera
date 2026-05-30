package com.astarworks.astera.match.application.eventbus.listeners.engagement

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.service.engagement.EngagementDashboardService
import com.astarworks.astera.core.domain.event.MatchEnded
import org.slf4j.LoggerFactory

/**
 * On [MatchEnded] kick a per-participant
 * [EngagementDashboardService.recomputeForPlayer] (Wave K 5integration-wire,
 * ADR-0128).
 *
 * # Background — the freshness drift
 *
 * Pre-Wave K, `EngagementDashboardService` recomputed on a 5-minute
 * scheduler. A player finishing a match would not see their score bump
 * until the next tick rolled — DAU/WAU computations stayed stale for
 * up to 5 minutes after the most signal-rich event in the player's
 * session (the match they just played). This listener closes the gap
 * by stamping `lastSeenAt = now` for every participant immediately on
 * match conclusion; the next dashboard `compute()` then sees the
 * up-to-date timestamps.
 *
 * # Participants source
 *
 * [MatchEnded] only carries the [com.astarworks.astera.core.domain.match.MatchId],
 * not the participant roster — the listener resolves the match via
 * [IMatchRepository], walks every team, and feeds each player id through
 * the dashboard service. Same indirection as
 * `RecordMatchPlayedOnMatchEndedListener` (Stats / Wave 5e); we don't
 * widen the event payload because most subscribers don't need the
 * roster.
 *
 * # Failure handling
 *
 * Per-player recomputes are logged at debug + swallowed — one player's
 * snapshot failing must not block the rest of the team. Match-lookup
 * miss (synthetic test publish) is logged at debug, not WARN.
 *
 * # Why match plugin
 *
 * The listener subscribes to a Core event ([MatchEnded]) and calls a
 * Core service ([EngagementDashboardService]). Either Core or Match
 * could host it; we put it in `:plugin:match` because the natural
 * coupling is "match lifecycle → snapshot refresh", same way
 * `RecordMatchPlayedOnMatchEndedListener` lives in `:plugin:stats`
 * (next to the rest of the match-driven stats wires).
 */
public class RecomputeEngagementSnapshotOnMatchEndedListener(
    private val matchRepository: IMatchRepository,
    private val engagement: EngagementDashboardService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val match = matchRepository.findById(event.matchId)
        if (match == null) {
            log.debug(
                "RecomputeEngagementSnapshotOnMatchEndedListener: match {} not found",
                event.matchId,
            )
            return
        }
        for (team in match.teams) {
            for (playerId in team.members) {
                runCatching { engagement.recomputeForPlayer(playerId) }
                    .onFailure { err ->
                        log.debug(
                            "RecomputeEngagementSnapshotOnMatchEndedListener: failed for player={} match={} err={}",
                            playerId, event.matchId, err.message,
                        )
                    }
            }
        }
    }
}
