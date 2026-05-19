package com.astarworks.astera.economy.domain.marketplace

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Lifecycle states of a [MarketplaceListing] (RTM #12, ADR-0035).
 *
 * Only [Active] listings are eligible for purchase. The terminal states
 * ([Sold] / [Cancelled] / [Expired]) keep the row in storage for audit /
 * history — listings are append-only at the row level; status mutations
 * happen via [MarketplaceListing.copy] + repository save.
 *
 * Phase 1 PoC: [Expired] is reserved for a future expiry scheduler (mirrors
 * `EnforceCurrencyPolicyUseCase`'s pattern). No code transitions to it yet.
 */
public sealed class ListingStatus {

    /** Eligible for purchase. */
    public data object Active : ListingStatus()

    /**
     * Purchase completed. [buyer] is the purchasing player and [soldAt] is
     * the instant the `PurchaseListingUseCase` marked the listing.
     */
    public data class Sold(
        public val buyer: PlayerId,
        public val soldAt: Instant,
    ) : ListingStatus()

    /** Seller-initiated cancellation via `CancelListingUseCase`. */
    public data object Cancelled : ListingStatus()

    /** Reached its expiry window without sale (reserved for future expiry job). */
    public data object Expired : ListingStatus()
}
