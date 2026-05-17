package com.astarworks.astera.core.domain.shop

import com.astarworks.astera.core.domain.economy.Currency

/**
 * The cost of a [ShopOffer] in a single currency.
 *
 * Wave 4.5 keeps prices single-currency. Multi-currency bundles
 * (`50 STAR + 1 GEM`) can land later via a `MultiPrice(List<ShopPrice>)`
 * variant — sealed escape hatch reserved for Phase 4 shop expansion.
 */
public data class ShopPrice(
    public val currency: Currency,
    public val amount: Long,
) {
    init {
        require(amount >= 0) { "ShopPrice.amount must be >= 0: $amount" }
    }
}
