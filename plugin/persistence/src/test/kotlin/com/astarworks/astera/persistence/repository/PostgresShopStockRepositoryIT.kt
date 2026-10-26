package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Round-trip IT for [PostgresShopStockRepository] (Wave 5g Core hardening)
 * against real Postgres via Testcontainers. Verifies the V19 migration
 * applies, seed is idempotent, and — crucially — that `SELECT … FOR
 * UPDATE` inside `decrementStock` serializes concurrent callers so two
 * threads racing on a `Limited(1)` offer produce **exactly one** success
 * and one [ShopStockError.OutOfStock], never two successes (which would
 * be the original Wave 5g bug: spec-read-but-never-decremented).
 */
public class PostgresShopStockRepositoryIT : PostgresIntegrationTestBase() {

    private val shopId = ShopId("it-shop")

    private fun offer(name: String): ShopOfferId = ShopOfferId(name)

    @Test
    public fun `seed then currentStock returns Limited(initial)`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        val offerId = offer("seed-basic")
        val seedResult = repo.seed(shopId, offerId, initial = 5)
        assertThat(seedResult.isSuccess).isTrue
        assertThat(repo.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(5))
        }
    }

    @Test
    public fun `currentStock returns null for unseeded offer`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        assertThat(repo.currentStock(shopId, offer("never-seeded"))).isNull()
        }
    }

    @Test
    public fun `seed is idempotent and does NOT overwrite existing remaining`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        val offerId = offer("idempotent-seed")
        repo.seed(shopId, offerId, initial = 10)
        // Simulate a purchase between two seeds (e.g. plugin restart after sales).
        repo.decrementStock(shopId, offerId, by = 4)
        assertThat(repo.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(6))

        // Re-seed with the YAML initial — must NOT reset remaining back to 10.
        val secondSeed = repo.seed(shopId, offerId, initial = 10)
        assertThat(secondSeed.isSuccess).isTrue
        assertThat(repo.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(6))
        }
    }

    @Test
    public fun `decrementStock for unknown offer returns UnknownOffer`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        val result = repo.decrementStock(shopId, offer("unknown"), by = 1)
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isInstanceOf(ShopStockError.UnknownOffer::class.java)
        }
    }

    @Test
    public fun `decrementStock returns OutOfStock when remaining is 0`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        val offerId = offer("oos-basic")
        repo.seed(shopId, offerId, initial = 1)
        val first = repo.decrementStock(shopId, offerId)
        assertThat(first.isSuccess).isTrue

        val second = repo.decrementStock(shopId, offerId)
        assertThat(second.isFailure).isTrue
        assertThat(second.errorOrNull())
            .isEqualTo(ShopStockError.OutOfStock(shopId, offerId))
        assertThat(repo.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(0))
        }
    }

    @Test
    public fun `concurrent decrement on Limited(1) — exactly one success and one OutOfStock`() {
        kotlinx.coroutines.runBlocking {
        val repo = PostgresShopStockRepository(db)
        val offerId = offer("race-${System.nanoTime()}")
        repo.seed(shopId, offerId, initial = 1)

        // Latch-coordinate so both threads call decrementStock at as close to
        // the same instant as possible — guarantees the SELECT FOR UPDATE
        // path actually serialises rather than running back-to-back.
        val start = CountDownLatch(1)
        val resultA = AtomicReference<Result<ShopStock, ShopStockError>>()
        val resultB = AtomicReference<Result<ShopStock, ShopStockError>>()

        val threadA = Thread({
            start.await()
            resultA.set(kotlinx.coroutines.runBlocking { repo.decrementStock(shopId, offerId) })
        }, "shop-stock-it-A")
        val threadB = Thread({
            start.await()
            resultB.set(kotlinx.coroutines.runBlocking { repo.decrementStock(shopId, offerId) })
        }, "shop-stock-it-B")

        threadA.start()
        threadB.start()
        start.countDown()
        threadA.join(TimeUnit.SECONDS.toMillis(15))
        threadB.join(TimeUnit.SECONDS.toMillis(15))

        val a = resultA.get()
        val b = resultB.get()
        assertThat(a).isNotNull
        assertThat(b).isNotNull

        val successes = listOf(a, b).count { it.isSuccess }
        val outOfStock = listOf(a, b).count {
            it.errorOrNull() is ShopStockError.OutOfStock
        }
        assertThat(successes).isEqualTo(1)
        assertThat(outOfStock).isEqualTo(1)

        // Final state: remaining == 0. Critically NOT -1 (the row-level lock
        // + V19 CHECK (remaining >= 0) constraint guarantee this).
        assertThat(repo.currentStock(shopId, offerId)).isEqualTo(ShopStock.Limited(0))
        }
}
}
