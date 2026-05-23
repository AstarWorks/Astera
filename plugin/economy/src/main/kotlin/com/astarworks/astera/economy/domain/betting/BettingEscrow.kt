package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Records that the per-side stakes of a [BettingMatch] have been debited
 * from the players' wallets and are being held by the escrow service
 * (Wave 5bet / ADR-0103).
 *
 * Lifecycle:
 *
 * 1. `IBettingEscrowService.hold(...)` debits both players via
 *    [com.astarworks.astera.core.application.port.outbound.ICurrencyLedger.debit]
 *    and writes one [BettingEscrow] (status [BettingEscrowStatus.Held]).
 * 2. `IBettingEscrowService.releaseToWinner(...)` credits the winner the
 *    full pot (minus house cut) and marks the escrow
 *    [BettingEscrowStatus.Paid].
 * 3. `IBettingEscrowService.refundBoth(...)` credits each side back its
 *    original stake and marks it [BettingEscrowStatus.Refunded] (used on
 *    draw / cancel / cleanup).
 *
 * Atomicity: each refund / release is bounded by the underlying
 * [com.astarworks.astera.core.application.port.outbound.ICurrencyLedger] —
 * the in-memory adapter is single-threaded by design; the Postgres overlay
 * (Wave 5bet) wraps both deposits in a single transaction.
 */
public data class BettingEscrow(
    val bettingMatchId: BettingMatchId,
    val challengerId: PlayerId,
    val opponentId: PlayerId,
    val currency: Currency,
    val perSideAmount: Long,
    val status: BettingEscrowStatus,
    val heldAt: Instant,
    val settledAt: Instant?,
) {
    init {
        require(perSideAmount > 0L) { "perSideAmount must be > 0, got $perSideAmount" }
        require(challengerId != opponentId) { "challenger and opponent must differ" }
        if (status == BettingEscrowStatus.Held) {
            require(settledAt == null) { "Held escrow must not have settledAt" }
        } else {
            require(settledAt != null) { "Non-Held escrow ($status) requires settledAt" }
        }
    }
}

public enum class BettingEscrowStatus {
    Held,
    Paid,
    Refunded,
}
