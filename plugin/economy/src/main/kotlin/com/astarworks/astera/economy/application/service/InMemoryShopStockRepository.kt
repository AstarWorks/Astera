package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IShopStockRepository] used as the default `priority=0`
 * registration in Wave 5g (Core hardening).
 *
 * Backed by a `ConcurrentHashMap<(ShopId, ShopOfferId), Int>`. The
 * persistence plugin's [com.astarworks.astera.persistence.repository.PostgresShopStockRepository]
 * overlays this at `priority=100` whenever Postgres is configured; until
 * then this fake survives single-JVM Phase 1 demos and unit tests.
 *
 * ## Thread safety
 *
 * [decrementStock] is implemented via [ConcurrentHashMap.compute], which
 * holds the per-key bucket lock for the duration of the lambda. Two
 * concurrent decrements for the same `(shopId, offerId)` therefore
 * serialise: the first sees `remaining = N`, writes `N-1`; the second
 * sees `N-1` (not `N`) and either writes `N-2` or fails with
 * [ShopStockError.OutOfStock] if `N-1 < by`.
 *
 * [currentStock] reads are eventually-consistent (no lock); callers must
 * NOT use the result to gate purchase — the only authoritative check is
 * [decrementStock]'s return value. Stock-display GUIs are fine with the
 * eventually-consistent read.
 *
 * ## Seeding
 *
 * [seed] is idempotent — `putIfAbsent` semantics, so re-running on
 * startup never resets a partially-bought offer back to the YAML initial.
 * Operators wanting to reset stock must clear the table (or for this
 * in-memory impl, restart the JVM).
 */
public class InMemoryShopStockRepository : IShopStockRepository {

    private val store: ConcurrentHashMap<Pair<ShopId, ShopOfferId>, Int> = ConcurrentHashMap()

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

        // Outcome captured inside compute so we can return the right
        // Result variant after the atomic section closes.
        var outcome: Result<ShopStock, ShopStockError>? = null
        store.compute(key) { _, current ->
            when {
                current == null -> {
                    outcome = Result.failure(ShopStockError.UnknownOffer(shopId, offerId))
                    null
                }
                current < by -> {
                    outcome = Result.failure(ShopStockError.OutOfStock(shopId, offerId))
                    current
                }
                else -> {
                    val next = current - by
                    outcome = Result.success(ShopStock.Limited(next))
                    next
                }
            }
        }
        return outcome ?: Result.failure(
            ShopStockError.Storage("InMemoryShopStockRepository.compute produced no outcome"),
        )
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
        store.computeIfPresent(shopId to offerId) { _, current -> minOf(current, max) }
        return Result.success(Unit)
    }

    override fun remove(shopId: ShopId, offerId: ShopOfferId): Result<Unit, ShopStockError> {
        store.remove(shopId to offerId)
        return Result.success(Unit)
    }

    /** Drop every seeded row. Test-only — production callers must restart the JVM. */
    public fun clear() {
        store.clear()
    }
}
