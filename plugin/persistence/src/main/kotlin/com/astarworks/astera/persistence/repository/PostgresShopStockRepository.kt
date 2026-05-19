package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError
import com.astarworks.astera.persistence.table.ShopStockTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.SQLException

/**
 * Postgres-backed [IShopStockRepository] (Wave 5g Core hardening).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default the economy plugin registers at priority=0.
 *
 * ## Atomicity
 *
 * [decrementStock] runs inside a single [transaction] and uses
 * `SELECT ... FOR UPDATE` (via Exposed v1 `Query.forUpdate()`) to take
 * a row-level lock on `(shop_id, offer_id)`. Two concurrent buyers race
 * for the lock; the winner reads `remaining = N`, writes `N - by`, and
 * commits; the loser blocks until the winner commits, then reads the
 * post-decrement value and decides between success and
 * [ShopStockError.OutOfStock] from there. Combined with the V19
 * `CHECK (remaining >= 0)` constraint, the row never settles in a
 * negative state.
 *
 * ## Seeding
 *
 * [seed] uses `insertIgnore` so re-running on plugin startup never
 * resets a partially-bought offer back to the YAML initial value —
 * existing rows are left untouched (the documented contract of
 * [IShopStockRepository.seed]).
 */
public class PostgresShopStockRepository(private val db: Database) : IShopStockRepository {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun currentStock(shopId: ShopId, offerId: ShopOfferId): ShopStock? = transaction(db) {
        ShopStockTable
            .selectAll()
            .where {
                (ShopStockTable.shopId eq shopId.value) and (ShopStockTable.offerId eq offerId.value)
            }
            .firstOrNull()
            ?.let { row -> ShopStock.Limited(row[ShopStockTable.remaining]) }
    }

    override fun decrementStock(
        shopId: ShopId,
        offerId: ShopOfferId,
        by: Int,
    ): Result<ShopStock, ShopStockError> {
        require(by >= 0) { "decrementStock 'by' must be >= 0, got $by" }
        return try {
            transaction(db) {
                val current = ShopStockTable
                    .selectAll()
                    .where {
                        (ShopStockTable.shopId eq shopId.value) and
                            (ShopStockTable.offerId eq offerId.value)
                    }
                    .forUpdate()
                    .firstOrNull()
                    ?.get(ShopStockTable.remaining)
                    ?: return@transaction Result.failure(
                        ShopStockError.UnknownOffer(shopId, offerId),
                    )

                if (current < by) {
                    return@transaction Result.failure(
                        ShopStockError.OutOfStock(shopId, offerId),
                    )
                }

                val next = current - by
                ShopStockTable.update(
                    {
                        (ShopStockTable.shopId eq shopId.value) and
                            (ShopStockTable.offerId eq offerId.value)
                    },
                ) {
                    it[remaining] = next
                }
                Result.success(ShopStock.Limited(next))
            }
        } catch (ex: SQLException) {
            // Most likely the V19 CHECK (remaining >= 0) constraint firing
            // because a racing transaction already decremented past zero
            // between our SELECT FOR UPDATE and UPDATE — surface as Storage
            // so the use case reverses the debit.
            log.warn("decrementStock SQL failure for shop={} offer={}", shopId.value, offerId.value, ex)
            Result.failure(ShopStockError.Storage(ex.message ?: "SQL failure"))
        }
    }

    override fun seed(
        shopId: ShopId,
        offerId: ShopOfferId,
        initial: Int,
    ): Result<Unit, ShopStockError> {
        require(initial >= 0) { "seed 'initial' must be >= 0, got $initial" }
        return try {
            transaction(db) {
                ShopStockTable.insertIgnore {
                    it[ShopStockTable.shopId] = shopId.value
                    it[ShopStockTable.offerId] = offerId.value
                    it[ShopStockTable.remaining] = initial
                }
            }
            Result.success(Unit)
        } catch (ex: SQLException) {
            log.warn("seed SQL failure for shop={} offer={}", shopId.value, offerId.value, ex)
            Result.failure(ShopStockError.Storage(ex.message ?: "SQL failure"))
        }
    }
}
