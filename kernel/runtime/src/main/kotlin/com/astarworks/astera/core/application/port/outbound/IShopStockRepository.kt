package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError

/**
 * Outbound port: mutable stock-view companion to [IShopRegistry].
 *
 * Wave 5g (Core hardening) split: [IShopRegistry] keeps the read-only spec
 * surface (`resolve / register / all`); this port owns the mutable per-offer
 * stock count. The Postgres impl (`PostgresShopStockRepository`, priority 100)
 * uses `SELECT ... FOR UPDATE` inside a transaction so `decrementStock` is
 * atomic across concurrent buyers; the in-memory impl
 * (`InMemoryShopStockRepository`, priority 0) is the default fallback.
 *
 * Seeded at plugin startup by `ShopLoaderService.loadFrom(...)`: every
 * [com.astarworks.astera.core.domain.shop.ShopOffer] whose
 * [com.astarworks.astera.core.domain.shop.ShopOffer.stock] is
 * [ShopStock.Limited] gets a row inserted via [seed] (idempotent).
 * Offers whose stock is [ShopStock.Unlimited] are never persisted —
 * `currentStock` returns null for them and callers short-circuit.
 */
public interface IShopStockRepository {

    /**
     * Current stock for `(shopId, offerId)`.
     *
     * Returns null when no row exists, which means either:
     * - the offer was authored as [ShopStock.Unlimited] and was therefore
     *   never seeded (callers must treat null as "no limit"), or
     * - the spec / loader is out of sync.
     */
    public fun currentStock(shopId: ShopId, offerId: ShopOfferId): ShopStock?

    /**
     * Atomically decrement remaining stock for `(shopId, offerId)` by [by].
     *
     * Returns the post-decrement [ShopStock.Limited] on success,
     * [ShopStockError.OutOfStock] when remaining would go negative,
     * [ShopStockError.UnknownOffer] when no row exists for the pair,
     * or [ShopStockError.Storage] on backend failure.
     *
     * Postgres impl uses `SELECT ... FOR UPDATE` inside a transaction so
     * concurrent calls serialize at the row level.
     */
    public fun decrementStock(
        shopId: ShopId,
        offerId: ShopOfferId,
        by: Int = 1,
    ): Result<ShopStock, ShopStockError>

    /**
     * Idempotent upsert: ensure a row exists for `(shopId, offerId)` with
     * [initial] remaining. If a row already exists, leave it unchanged
     * (do **not** reset to [initial] — restart-safe).
     *
     * Called by `ShopLoaderService` at plugin startup for every authored
     * [ShopStock.Limited] offer.
     */
    public fun seed(
        shopId: ShopId,
        offerId: ShopOfferId,
        initial: Int,
    ): Result<Unit, ShopStockError>

    /**
     * Lower the remaining stock of `(shopId, offerId)` to at most [max] (a reload that lowers an
     * offer's limit; stock below the new limit is kept). No row = no-op.
     */
    public fun clampTo(shopId: ShopId, offerId: ShopOfferId, max: Int): Result<Unit, ShopStockError>

    /** Drop the row of `(shopId, offerId)` (offer removed or no longer limited). No row = no-op. */
    public fun remove(shopId: ShopId, offerId: ShopOfferId): Result<Unit, ShopStockError>
}
