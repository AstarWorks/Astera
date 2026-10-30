package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError

/**
 * In-memory [IShopStockRepository] for use case tests (Wave 5g Core
 * hardening).
 *
 * Pure single-threaded — backing store is a plain [MutableMap], not a
 * [java.util.concurrent.ConcurrentHashMap]. Use-case tests run on the
 * JUnit thread; concurrent decrement behaviour belongs in
 * `PostgresShopStockRepositoryIT` against a real row-locked Postgres.
 *
 * Mirrors [FakePlayerRepository]'s shape: zero-arg constructor +
 * `linkedMapOf` backing for deterministic iteration order in test
 * assertions.
 */
public class FakeShopStockRepository : IShopStockRepository {

    private val store: MutableMap<Pair<ShopId, ShopOfferId>, Int> = linkedMapOf()

    override fun currentStock(shopId: ShopId, offerId: ShopOfferId): ShopStock? {
        val remaining = store[shopId to offerId] ?: return null
        return ShopStock.Limited(remaining)
    }

    override fun decrementStock(
        shopId: ShopId,
        offerId: ShopOfferId,
        by: Int,
    ): Result<ShopStock, ShopStockError> {
        require(by >= 0) { "decrementStock 'by' must be >= 0, got $by" }
        val key = shopId to offerId
        val current = store[key] ?: return Result.failure(ShopStockError.UnknownOffer(shopId, offerId))
        if (current < by) {
            return Result.failure(ShopStockError.OutOfStock(shopId, offerId))
        }
        val next = current - by
        store[key] = next
        return Result.success(ShopStock.Limited(next))
    }

    override fun seed(
        shopId: ShopId,
        offerId: ShopOfferId,
        initial: Int,
    ): Result<Unit, ShopStockError> {
        require(initial >= 0) { "seed 'initial' must be >= 0, got $initial" }
        store.putIfAbsent(shopId to offerId, initial)
        return Result.success(Unit)
    }

    override fun clampTo(shopId: ShopId, offerId: ShopOfferId, max: Int): Result<Unit, ShopStockError> {
        require(max >= 0) { "clampTo 'max' must be >= 0, got $max" }
        store[shopId to offerId]?.let { store[shopId to offerId] = minOf(it, max) }
        return Result.success(Unit)
    }

    override fun remove(shopId: ShopId, offerId: ShopOfferId): Result<Unit, ShopStockError> {
        store.remove(shopId to offerId)
        return Result.success(Unit)
    }

    /** Test-only: drop every seeded row. */
    public fun clear() {
        store.clear()
    }
}
