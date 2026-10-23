package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.economy.Currency

/**
 * Per-side stake for a [BettingMatch] (Wave 5bet / ADR-0103).
 *
 * Both challenger and opponent put up an identical [amount] of [currency]
 * (symmetric stake — asymmetric odds are explicitly out of scope per
 * ADR-0103 §"Workflow"). The pair of stakes is held in escrow by the
 * `IBettingEscrowService` from accept-time until resolution; on resolve the
 * winner receives `2 × amount` (minus any house cut declared in
 * [com.astarworks.astera.economy.domain.betting.BettingPolicy.payoutSplit]).
 *
 * ## Currency constraint (ADR-0103 §"Constraints (compliance + safety)")
 *
 * Per the JP regulatory posture inherited from ADR-0035 (CurrencyPolicy):
 *
 * - Wagering is permitted **only** with in-game currencies that are NOT
 *   redeemable for cash. Currently that means [Currency.GOLD] (account-bound). [Currency.GEM] is **forbidden**
 *   because it is a paid premium currency; gambling paid currency could be
 *   reclassified as gambling real money under JP law.
 *
 * The check lives in [BetStake.init] so domain construction rejects an
 * invalid stake at the boundary — use cases never see a GEM stake.
 */
public data class BetStake(
    val currency: Currency,
    val amount: Long,
) {
    init {
        require(amount > 0L) { "stake amount must be > 0, got $amount" }
        require(currency != Currency.GEM) {
            "Currency.GEM is not eligible for betting (paid premium currency, " +
                "ADR-0103 / ADR-0035 JP compliance)"
        }
    }
}
