package com.astarworks.astera.core.domain.shop

/**
 * How much of a [ShopOffer] is available for purchase.
 *
 * Sealed so the purchase use case's `when` is exhaustive — adding a new
 * variant (e.g. `PeriodLimited(per: Period, max: Int)` for daily limits) is
 * a deliberate change, not something a contributor can miss.
 *
 * Wave 4.5 ships [Unlimited] + [Limited]. Per-player limits, rotation, and
 * time-bounded stock are Phase 4 follow-ups.
 */
public sealed interface ShopStock {
    public data object Unlimited : ShopStock

    public data class Limited(public val remaining: Int) : ShopStock {
        init {
            require(remaining >= 0) { "ShopStock.Limited.remaining must be >= 0: $remaining" }
        }
    }
}
