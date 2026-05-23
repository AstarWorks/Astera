package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingEscrowService
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingEscrow
import com.astarworks.astera.economy.domain.betting.BettingEscrowStatus
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IBettingEscrowService] (Wave 5bet / ADR-0103).
 *
 * Stake handling is delegated to [ICurrencyLedger]:
 *
 * - [hold] debits both players; on either debit failure the previously-debited
 *   side is refunded so the operation is atomic from the players' view.
 * - [releaseToWinner] credits the winner `floor(2 × perSideAmount × split)`.
 * - [refundBoth] credits each player their original stake.
 *
 * Phase 1 single-threaded by design (Paper main thread). Postgres overlay
 * wraps each call in a SQL transaction (Wave 5bet `:plugin:persistence`).
 */
public class InMemoryBettingEscrowService(
    private val ledger: ICurrencyLedger,
) : IBettingEscrowService {

    private val byMatch: MutableMap<BettingMatchId, BettingEscrow> = ConcurrentHashMap()

    override fun hold(
        bettingMatchId: BettingMatchId,
        challengerId: PlayerId,
        opponentId: PlayerId,
        currency: Currency,
        perSideAmount: Long,
        now: Instant,
    ): Result<BettingEscrow, BettingError> {
        require(challengerId != opponentId) { "challenger and opponent must differ" }
        require(perSideAmount > 0L) { "perSideAmount must be > 0, got $perSideAmount" }
        val existing = byMatch[bettingMatchId]
        if (existing != null) return Result.failure(BettingError.AlreadyEscrowed(bettingMatchId))

        val challengerDebit = ledger.debit(challengerId, currency, perSideAmount)
        if (challengerDebit is Result.Failure) {
            return Result.failure(BettingError.EscrowDebitFailed(challengerId, challengerDebit.error))
        }
        val opponentDebit = ledger.debit(opponentId, currency, perSideAmount)
        if (opponentDebit is Result.Failure) {
            // Refund challenger so the held-escrow invariant holds (both or neither).
            ledger.credit(challengerId, currency, perSideAmount)
            return Result.failure(BettingError.EscrowDebitFailed(opponentId, opponentDebit.error))
        }

        val escrow = BettingEscrow(
            bettingMatchId = bettingMatchId,
            challengerId = challengerId,
            opponentId = opponentId,
            currency = currency,
            perSideAmount = perSideAmount,
            status = BettingEscrowStatus.Held,
            heldAt = now,
            settledAt = null,
        )
        byMatch[bettingMatchId] = escrow
        return Result.success(escrow)
    }

    override fun releaseToWinner(
        bettingMatchId: BettingMatchId,
        winnerId: PlayerId,
        payoutSplit: Double,
        now: Instant,
    ): Result<BettingEscrow, BettingError> {
        require(payoutSplit > 0.0 && payoutSplit <= 1.0) {
            "payoutSplit must be in (0.0, 1.0], got $payoutSplit"
        }
        val escrow = byMatch[bettingMatchId]
            ?: return Result.failure(BettingError.EscrowMissing(bettingMatchId))
        if (escrow.status == BettingEscrowStatus.Paid) {
            return Result.success(escrow)
        }
        require(escrow.status == BettingEscrowStatus.Held) {
            "cannot release escrow in status ${escrow.status}"
        }
        require(winnerId == escrow.challengerId || winnerId == escrow.opponentId) {
            "winnerId ($winnerId) must be challenger or opponent of escrow $bettingMatchId"
        }

        val pot = escrow.perSideAmount * 2L
        val payout = Math.floorDiv((pot.toDouble() * payoutSplit).toLong(), 1L)
        if (payout > 0L) {
            ledger.credit(winnerId, escrow.currency, payout)
        }
        val updated = escrow.copy(status = BettingEscrowStatus.Paid, settledAt = now)
        byMatch[bettingMatchId] = updated
        return Result.success(updated)
    }

    override fun refundBoth(
        bettingMatchId: BettingMatchId,
        now: Instant,
    ): Result<BettingEscrow, BettingError> {
        val escrow = byMatch[bettingMatchId]
            ?: return Result.failure(BettingError.EscrowMissing(bettingMatchId))
        if (escrow.status == BettingEscrowStatus.Refunded || escrow.status == BettingEscrowStatus.Paid) {
            return Result.success(escrow)
        }
        require(escrow.status == BettingEscrowStatus.Held) {
            "cannot refund escrow in status ${escrow.status}"
        }
        ledger.credit(escrow.challengerId, escrow.currency, escrow.perSideAmount)
        ledger.credit(escrow.opponentId, escrow.currency, escrow.perSideAmount)
        val updated = escrow.copy(status = BettingEscrowStatus.Refunded, settledAt = now)
        byMatch[bettingMatchId] = updated
        return Result.success(updated)
    }

    override fun find(bettingMatchId: BettingMatchId): BettingEscrow? = byMatch[bettingMatchId]

    /** Phase 1 test helper. */
    public fun clear() {
        byMatch.clear()
    }
}
