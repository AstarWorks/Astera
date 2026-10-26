package com.astarworks.astera.economy.application.port.outbound.betting

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.betting.BettingEscrow
import com.astarworks.astera.economy.domain.betting.BettingError
import com.astarworks.astera.economy.domain.betting.BettingMatchId
import java.time.Instant

/**
 * Outbound port: hold + release / refund per-side stakes of a [BettingEscrow]
 * (Wave 5bet / ADR-0103).
 *
 * Conceptually a thin wrapper over [com.astarworks.astera.core.application.port.outbound.ICurrencyLedger]
 * that:
 *
 * - debits both players atomically on accept,
 * - persists a [BettingEscrow] record for audit,
 * - credits winner / refunds both on resolve,
 *
 * The Phase 1 in-memory impl is single-threaded (Paper main thread). The
 * Postgres overlay (Wave 5bet, `plugin:persistence` V39 `bet_escrows`) wraps
 * each operation in a single SQL transaction.
 *
 * # Why a separate port from `ICurrencyLedger`
 *
 * The Wave 5e audit log is the SSOT for compliance — but `ICurrencyLedger`
 * publishes [com.astarworks.astera.core.domain.event.CurrencyChanged] with a
 * polymorphic [com.astarworks.astera.core.domain.event.CurrencyChangeReason].
 * The betting flow uses a dedicated `CurrencyChangeReason.BettingEscrow*`
 * variant (added in `core.domain.event.CurrencyChangeReason` by this wave)
 * so analytics can distinguish escrow movement from refund / payout movement
 * without inferring from string tags.
 */
public interface IBettingEscrowService {

    /**
     * Atomically debit [perSideAmount] of [currency] from BOTH [challengerId]
     * and [opponentId] (the bet is symmetric), persist a
     * [com.astarworks.astera.economy.domain.betting.BettingEscrow] with
     * `status = Held`, and return it. Fails with
     * [BettingError.EscrowDebitFailed] if either side has insufficient funds
     * (the other side is NOT debited in that case).
     *
     * Idempotent on [bettingMatchId]: a subsequent call for the same id
     * returns [BettingError.AlreadyEscrowed].
     */
    public suspend fun hold(
        bettingMatchId: BettingMatchId,
        challengerId: PlayerId,
        opponentId: PlayerId,
        currency: Currency,
        perSideAmount: Long,
        now: Instant,
    ): Result<BettingEscrow, BettingError>

    /**
     * Credit the winner the full pot (`2 × perSideAmount`) scaled by
     * [payoutSplit] (Phase 1 always 1.0). Returns the escrow with
     * `status = Paid`. Fails [BettingError.EscrowMissing] if no Held
     * escrow exists for [bettingMatchId].
     *
     * Idempotent on [bettingMatchId]: a re-call after Paid returns the
     * already-paid escrow without re-crediting.
     */
    public suspend fun releaseToWinner(
        bettingMatchId: BettingMatchId,
        winnerId: PlayerId,
        payoutSplit: Double,
        now: Instant,
    ): Result<BettingEscrow, BettingError>

    /**
     * Credit each side back its original stake. Returns the escrow with
     * `status = Refunded`. Idempotent.
     */
    public suspend fun refundBoth(
        bettingMatchId: BettingMatchId,
        now: Instant,
    ): Result<BettingEscrow, BettingError>

    public suspend fun find(bettingMatchId: BettingMatchId): BettingEscrow?
}
