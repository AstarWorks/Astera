package com.astarworks.astera.economy.domain.marketplace

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import java.time.Instant
import java.util.UUID

/**
 * Opaque identifier for a [MarketplaceListing] (RTM #12, ADR-0035).
 *
 * Wrapped UUID rather than a string id because listings are produced by the
 * system at run time (no human-authored ids — contrast with `ItemId` /
 * `ShopId` which originate from `content/`). The Postgres column type is
 * `UUID` (V12), so the value class' representation matches the storage type
 * one-to-one.
 */
@JvmInline
public value class ListingId(public val value: UUID) {
    override fun toString(): String = value.toString()
}

/**
 * A player-authored marketplace listing (RTM #12, ADR-0035).
 *
 * Aggregate root of the player-marketplace domain. The listing carries every
 * fact the marketplace use cases need to evaluate a purchase — seller,
 * itemId, price, listing timestamp, and current [status] — without reaching
 * out to other aggregates.
 *
 * Phase 1 PoC ownership model: the seller's actual inventory possession is
 * trusted at listing time (the `CreateListingUseCase` cannot escrow the item
 * yet because Wave 6 will land the inventory adapter). Status transitions
 * model the listing's lifecycle, not the item's escrow state.
 */
public data class MarketplaceListing(
    public val id: ListingId,
    public val seller: PlayerId,
    public val itemId: ItemId,
    public val price: ShopPrice,
    public val listedAt: Instant,
    public val status: ListingStatus,
)
