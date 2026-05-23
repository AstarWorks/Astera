package com.astarworks.astera.economy.application.eventbus.listeners.betting

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.BettingMatchResolved
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingEndReason
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingStatus
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Bridges [MatchEnded] (published by the Match plugin) to the betting
 * lifecycle (Wave 5bet / ADR-0103 §"Workflow" step 4).
 *
 * If the ended match is tracked by an [BettingStatus.Active] [BettingMatch]:
 *
 * 1. Resolve the winner — the side whose [PlayerId] is in the winning team.
 *    A null winning team OR a winning team that contains NEITHER side maps
 *    to [BettingEndReason.Draw] (refund both).
 * 2. Move the match to [BettingStatus.Resolved] with the resolution payload.
 * 3. Publish [BettingMatchResolved] — the payout listener consumes that and
 *    finalises the escrow.
 *
 * # Why split resolve from payout
 *
 * Audit + analytics get clean signal:
 *
 * - "match resolved" event without "payout distributed" → the escrow service
 *   degraded and operator should retry the payout.
 * - "payout distributed" never fires without a "resolved" → no orphaned
 *   payouts.
 *
 * Same split rationale as ADR-0035 marketplace listing vs. fulfillment.
 */
public class ResolveBettingOnMatchEndedListener(
    private val bettingMatches: IBettingMatchRepository,
    private val matchRepository: IMatchRepository,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: MatchEnded) {
        val betting = bettingMatches.findByMatchId(event.matchId) ?: return
        if (betting.status != BettingStatus.Active) {
            log.debug(
                "ResolveBettingOnMatchEndedListener: betting={} status={} skip",
                betting.id, betting.status,
            )
            return
        }

        val endReason = endReasonFor(event.reason)
        val winner = winningPlayer(event.winningTeam, betting)

        val resolvedReason = when {
            endReason is BettingEndReason.AdminCancelled -> endReason
            endReason is BettingEndReason.CancelledWithReason -> endReason
            winner == null -> BettingEndReason.Draw
            else -> BettingEndReason.MatchOutcome
        }
        val resolved = bettingMatches.save(
            betting.resolve(
                winnerId = if (resolvedReason == BettingEndReason.MatchOutcome) winner else null,
                endReason = resolvedReason,
                now = clock.instant(),
            ),
        )

        eventBus.publish(
            BettingMatchResolved(
                bettingMatchUuid = resolved.id.value,
                matchId = event.matchId,
                challengerId = resolved.challengerId,
                opponentId = resolved.opponentId,
                winnerId = resolved.winnerId,
                endReasonKind = endReasonKindOf(resolvedReason),
            ),
        )
    }

    private fun endReasonFor(reason: EndReason): BettingEndReason = when (reason) {
        is EndReason.AdminEnded -> BettingEndReason.AdminCancelled
        is EndReason.Cancelled -> BettingEndReason.CancelledWithReason(reason.why)
        else -> BettingEndReason.MatchOutcome
    }

    /**
     * The winning [PlayerId] of the betting pair, if the [winningTeam]
     * contains exactly one of (challenger, opponent). Otherwise null
     * (= refund-both / draw).
     */
    private fun winningPlayer(winningTeam: TeamId?, betting: BettingMatch): PlayerId? {
        if (winningTeam == null) return null
        val matchId = betting.matchId ?: return null
        val match = matchRepository.findById(matchId) ?: return null
        val winners = match.teams.firstOrNull { it.id == winningTeam }?.members.orEmpty()
        val challengerWon = betting.challengerId in winners
        val opponentWon = betting.opponentId in winners
        return when {
            challengerWon && !opponentWon -> betting.challengerId
            opponentWon && !challengerWon -> betting.opponentId
            else -> null
        }
    }

    private fun endReasonKindOf(reason: BettingEndReason): String = when (reason) {
        BettingEndReason.MatchOutcome -> "match_outcome"
        BettingEndReason.Draw -> "draw"
        BettingEndReason.AdminCancelled -> "admin_cancelled"
        is BettingEndReason.CancelledWithReason -> "cancelled_with_reason"
    }
}
