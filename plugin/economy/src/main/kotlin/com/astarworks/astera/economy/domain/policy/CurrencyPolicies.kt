package com.astarworks.astera.economy.domain.policy

import com.astarworks.astera.core.domain.economy.Currency

/**
 * Default per-[Currency] regulatory posture (Wave 5f / ADR-0035 / RTM #19).
 *
 * Phase 1 PoC hard-codes the JP 資金決済法 (Payment Services Act) compliant
 * shape:
 *  - **STAR** (match-bound): non-tradable, non-redeemable, 180-day expiry,
 *    no circulation cap. `Earned` source class.
 *  - **GOLD** (account-bound): tradable, non-redeemable, 180-day expiry,
 *    ¥10M circulation cap. `Earned` source class.
 *  - **GEM** (premium / paid): non-tradable, non-redeemable, 180-day expiry,
 *    ¥10M circulation cap. `Purchased` source class.
 *
 * Phase 4+ may externalize this to `content/economy/currency-policy.yaml`;
 * the in-memory map is intentionally simple to land Phase 1 within scope.
 */
public object CurrencyPolicies {

    /** JP 資金決済法 (Payment Services Act) Article 13: prepaid value expires after 6 months unless re-issued. */
    public const val JP_EXPIRY_DAYS: Int = 180

    /** JP 資金決済法 Article 14: ¥10M circulation cap triggers registration requirements; we use it as a hard ceiling. */
    public const val JP_CAP_YEN: Long = 10_000_000L

    private val defaults: Map<Currency, CurrencyPolicy> = mapOf(
        Currency.STAR to CurrencyPolicy(
            currency = Currency.STAR,
            expirable = true,
            expirationDays = JP_EXPIRY_DAYS,
            circulationCap = null,
            redeemable = false,
            tradable = false,
            sourceClass = SourceClass.Earned,
        ),
        Currency.GOLD to CurrencyPolicy(
            currency = Currency.GOLD,
            expirable = true,
            expirationDays = JP_EXPIRY_DAYS,
            circulationCap = JP_CAP_YEN,
            redeemable = false,
            tradable = true,
            sourceClass = SourceClass.Earned,
        ),
        Currency.GEM to CurrencyPolicy(
            currency = Currency.GEM,
            expirable = true,
            expirationDays = JP_EXPIRY_DAYS,
            circulationCap = JP_CAP_YEN,
            redeemable = false,
            tradable = false,
            sourceClass = SourceClass.Purchased,
        ),
    )

    /** Resolve the default [CurrencyPolicy] for [currency]. Total over [Currency]. */
    public fun defaultFor(currency: Currency): CurrencyPolicy =
        defaults.getValue(currency)

    /** Construct a `policyOf` function suitable for [com.astarworks.astera.economy.application.usecase.policy.EnforceCurrencyPolicyUseCase]. */
    public fun lookup(): (Currency) -> CurrencyPolicy = ::defaultFor
}
