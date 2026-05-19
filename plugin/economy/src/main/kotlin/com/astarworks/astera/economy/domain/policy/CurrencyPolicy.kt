package com.astarworks.astera.economy.domain.policy

import com.astarworks.astera.core.domain.economy.Currency

/**
 * Per-currency regulatory policy (RTM #19, JP 資金決済法 compliance).
 *
 * The 4 enforcement variants (expirable + cap + redeemable + tradable) are
 * orthogonal — different real-world Currency variants pick the right
 * combination. JP prepaid-value rules require [redeemable] = false and
 * either [expirationDays] non-null OR [circulationCap] non-null.
 *
 * Wave 5f (ADR-0035): introduced together with [com.astarworks.astera.economy.application.port.outbound.ICurrencyIssuanceLog]
 * + [com.astarworks.astera.economy.application.usecase.policy.ExpireCurrencyUseCase].
 * Production binding is a `Map<Currency, CurrencyPolicy>` provided at plugin
 * enable (Phase C wiring).
 */
public data class CurrencyPolicy(
    public val currency: Currency,
    /** True if balance expires after [expirationDays] days from issuance. */
    public val expirable: Boolean,
    /** Days until expiry. Required when [expirable] = true. JP rule: 180 days. */
    public val expirationDays: Int?,
    /** Outstanding circulation cap (sum of all wallets). JP rule: ¥10_000_000. */
    public val circulationCap: Long?,
    /** True if balance can be exchanged back to legal tender. JP rule: false. */
    public val redeemable: Boolean,
    /** True if balance can be transferred player-to-player. */
    public val tradable: Boolean,
    public val sourceClass: SourceClass,
) {
    init {
        if (expirable) {
            require(expirationDays != null && expirationDays > 0) {
                "expirable policy needs positive expirationDays"
            }
        }
        if (circulationCap != null) {
            require(circulationCap > 0) { "circulationCap must be positive" }
        }
    }
}
