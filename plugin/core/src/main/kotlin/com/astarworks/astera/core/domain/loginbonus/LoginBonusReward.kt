package com.astarworks.astera.core.domain.loginbonus

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId

/**
 * Reward payload granted on a successful daily login claim
 * (Wave J 5server-content-ops, ADR-0127 — Phase 2 ready skeleton).
 *
 * The Phase 2 grant step (currency credit + item delivery) is deferred — Phase 1
 * surfaces this type so YAML authors can describe day-by-day reward tables
 * and tests can assert against the shape.
 */
public data class LoginBonusReward(
    public val currency: Currency,
    public val amount: Long,
    public val items: List<ItemId> = emptyList(),
) {
    init {
        require(amount >= 0L) { "amount must be >= 0 (got $amount)" }
    }
}
