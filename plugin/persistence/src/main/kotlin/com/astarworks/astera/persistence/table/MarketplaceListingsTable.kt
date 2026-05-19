package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `marketplace_listings` table — player-authored listings (RTM #12, ADR-0035).
 *
 * Wave 5f, V12 migration. One row per listing keyed by [id] (UUID, matches
 * [com.astarworks.astera.economy.domain.marketplace.ListingId.value]).
 * Status is stored as the [com.astarworks.astera.economy.domain.marketplace.ListingStatus]
 * sealed-class name (ACTIVE / SOLD / CANCELLED / EXPIRED) as VARCHAR for the
 * same forward-compatibility reason `loadouts.slot` is — adding a new status
 * variant must not require an `ALTER TYPE`.
 *
 * Sold-state side data lives in nullable columns ([buyerId] / [soldAt]); the
 * V12 schema does not enforce the "non-null when status = SOLD" pairing in
 * SQL because Phase 1 PoC keeps the assertion at the repository's
 * domain-to-row conversion layer.
 */
public object MarketplaceListingsTable : Table("marketplace_listings") {
    public val id = javaUUID("id")
    public val sellerId = javaUUID("seller_id")
    public val itemId = varchar("item_id", 128)
    public val priceCurrency = varchar("price_currency", 16)
    public val priceAmount = long("price_amount")
    public val listedAt = timestamp("listed_at")
    public val status = varchar("status", 16)
    public val buyerId = javaUUID("buyer_id").nullable()
    public val soldAt = timestamp("sold_at").nullable()
    public val cancelledAt = timestamp("cancelled_at").nullable()
    public val expiredAt = timestamp("expired_at").nullable()
    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
