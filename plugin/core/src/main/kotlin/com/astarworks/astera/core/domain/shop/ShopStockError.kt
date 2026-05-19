package com.astarworks.astera.core.domain.shop

/**
 * Failure modes for `IShopStockRepository` mutations.
 *
 * Wave 5g: spec is immutable ([ShopSpec]/[ShopOffer]) and lives in
 * [com.astarworks.astera.core.application.port.outbound.IShopRegistry]; stock
 * is mutable and lives in
 * [com.astarworks.astera.core.application.port.outbound.IShopStockRepository].
 * Splitting the two ports keeps the spec read-only across the JVM while
 * letting the stock view participate in row-locked transactions.
 */
public sealed class ShopStockError {
    /** The offer exists but its stock has hit zero. */
    public data class OutOfStock(public val shopId: ShopId, public val offerId: ShopOfferId) : ShopStockError()

    /** No stock row found for `(shopId, offerId)`. Typically a missing seed. */
    public data class UnknownOffer(public val shopId: ShopId, public val offerId: ShopOfferId) : ShopStockError()

    /** Storage layer (Postgres / in-memory) reported an error. */
    public data class Storage(public val detail: String) : ShopStockError()
}
