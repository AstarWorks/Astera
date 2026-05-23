package com.astarworks.astera.economy.application.eventbus.listeners.betting

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.event.BettingMatchResolved
import com.astarworks.astera.core.domain.event.BettingPayoutDistributed
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.event.CurrencyChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingPolicyRegistry
import com.astarworks.astera.economy.domain.betting.BettingMatch
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.UUID

/**
 * Consumes [BettingMatchResolved] and finalises the escrow flow (Wave 5bet
 * / ADR-0103 §"Workflow" step 4–5):
 *
 * - Winner present (`endReasonKind = match_outcome`) → release pot to winner;
 *   updates loser's [com.astarworks.astera.economy.domain.betting.LossStreakState].
 * - Winner null (`draw` / `admin_cancelled` / `cancelled_with_reason`) → refund
 *   both sides; no loss-streak increment.
 *
 * Publishes:
 *
 * - [BettingPayoutDistributed] — single audit-grade event with the final fund flow.
 * - Per-side [CurrencyChanged] with `CurrencyChangeReason.Betting` for analytics
 *   (kind = `winner_payout` or `refund`).
 */
public class DistributeBettingPayoutOnResolvedListener(
    private val bettingMatches: IBettingMatchRepository,
    private val escrow: IBettingEscrowService,
    private val history: IBettingHistoryService,
    private val policies: IBettingPolicyRegistry,
    private val ledger: ICurrencyLedger,
    private val eventBus: IDomainEventBus,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(event: BettingMatchResolved) {
        val bettingMatchId = BettingMatchId(event.bettingMatchUuid)
        val betting = bettingMatches.findById(bettingMatchId)
        if (betting == null) {
            log.debug(
                "DistributeBettingPayoutOnResolvedListener: betting={} not found, skip",
                bettingMatchId,
            )
            return
        }
        val policy = policies.get(betting.policyId)
        if (policy == null) {
            log.debug(
                "DistributeBettingPayoutOnResolvedListener: policy={} not found for betting={}, skip",
                betting.policyId, bettingMatchId,
            )
            return
        }
        val now = clock.instant()

        val winnerId = event.winnerId
        if (winnerId == null) {
            distributeRefund(betting, bettingMatchId, now)
        } else {
            distributeWinnerPayout(
                betting = betting,
                bettingMatchId = bettingMatchId,
                winnerId = winnerId,
                payoutSplit = policy.payoutSplit,
                lossStreakLimit = policy.lossStreakLimit,
                lossStreakCooldownMs = policy.lossStreakCooldown.inWholeMilliseconds,
                now = now,
            )
        }
    }

    private fun distributeRefund(
        betting: BettingMatch,
        bettingMatchId: BettingMatchId,
        now: java.time.Instant,
    ) {
        val refundResult = escrow.refundBoth(bettingMatchId, now)
        if (refundResult is Result.Failure) {
            log.warn(
                "DistributeBettingPayoutOnResolvedListener: refund failed betting={} err={}",
                bettingMatchId, refundResult.error,
            )
            return
        }
        // Reset loss-streak on draw / cancel (didn't lose, didn't win).
        history.saveLossStreak(history.lossStreakOf(betting.challengerId).reset(now))
        history.saveLossStreak(history.lossStreakOf(betting.opponentId).reset(now))
        history.recordResolution(betting.challengerId, now)
        history.recordResolution(betting.opponentId, now)

        publishCurrencyChange(betting.challengerId, betting.stake.currency, betting.stake.amount, betting.id.value, "refund")
        publishCurrencyChange(betting.opponentId, betting.stake.currency, betting.stake.amount, betting.id.value, "refund")
        eventBus.publish(
            BettingPayoutDistributed(
                bettingMatchUuid = betting.id.value,
                challengerId = betting.challengerId,
                opponentId = betting.opponentId,
                winnerId = null,
                currency = betting.stake.currency,
                winnerPayout = 0L,
                refundEach = betting.stake.amount,
                outcomeKind = "refunded_both",
            ),
        )
    }

    private fun distributeWinnerPayout(
        betting: BettingMatch,
        bettingMatchId: BettingMatchId,
        winnerId: PlayerId,
        payoutSplit: Double,
        lossStreakLimit: Int,
        lossStreakCooldownMs: Long,
        now: java.time.Instant,
    ) {
        val releaseResult = escrow.releaseToWinner(bettingMatchId, winnerId, payoutSplit, now)
        if (releaseResult is Result.Failure) {
            log.warn(
                "DistributeBettingPayoutOnResolvedListener: release failed betting={} err={}",
                bettingMatchId, releaseResult.error,
            )
            return
        }
        val payout = ((betting.stake.amount * 2L).toDouble() * payoutSplit).toLong()
        val loserId = if (winnerId == betting.challengerId) betting.opponentId else betting.challengerId

        // Winner: reset loss-streak. Loser: apply loss (may open cooldown).
        history.saveLossStreak(history.lossStreakOf(winnerId).reset(now))
        history.saveLossStreak(
            history.lossStreakOf(loserId).applyLoss(now, lossStreakLimit, lossStreakCooldownMs),
        )
        history.recordResolution(winnerId, now)
        history.recordResolution(loserId, now)

        publishCurrencyChange(winnerId, betting.stake.currency, payout, betting.id.value, "winner_payout")
        eventBus.publish(
            BettingPayoutDistributed(
                bettingMatchUuid = betting.id.value,
                challengerId = betting.challengerId,
                opponentId = betting.opponentId,
                winnerId = winnerId,
                currency = betting.stake.currency,
                winnerPayout = payout,
                refundEach = 0L,
                outcomeKind = "winner_paid",
            ),
        )
    }

    private fun publishCurrencyChange(
        playerId: PlayerId,
        currency: Currency,
        delta: Long,
        bettingMatchUuid: UUID,
        kind: String,
    ) {
        if (delta <= 0L) return
        val balance = ledger.balance(playerId, currency)
        eventBus.publish(
            CurrencyChanged(
                playerId = playerId,
                currency = currency,
                delta = delta,
                newBalance = balance,
                reason = CurrencyChangeReason.Betting(
                    bettingMatchUuid = bettingMatchUuid,
                    kind = kind,
                ),
            ),
        )
    }
}
