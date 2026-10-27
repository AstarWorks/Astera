package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Round-trip IT for [PostgresMarketplaceRepository] (Wave 5f / RTM #12 /
 * ADR-0035) against a real Postgres via Testcontainers. Verifies the V12
 * migration applies and that save / findById / findActiveBySeller /
 * findAllActive preserve the [MarketplaceListing] aggregate including the
 * Sold-state side data.
 */
public class PostgresMarketplaceRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `save + findById round-trips an Active listing`() {
        val repo = PostgresMarketplaceRepository(db)
        val id = ListingId(UUID.randomUUID())
        val seller = PlayerId(UUID.randomUUID())
        val listing = MarketplaceListing(
            id = id,
            seller = seller,
            itemId = ItemId("example-sword"),
            price = ShopPrice(Currency.GOLD, 500L),
            listedAt = Instant.parse("2026-05-19T10:00:00Z"),
            status = ListingStatus.Active,
        )
        repo.save(listing)
        val found = repo.findById(id)
        assertThat(found).isNotNull
        assertThat(found!!.seller).isEqualTo(seller)
        assertThat(found.itemId).isEqualTo(ItemId("example-sword"))
        assertThat(found.price).isEqualTo(ShopPrice(Currency.GOLD, 500L))
        assertThat(found.status).isEqualTo(ListingStatus.Active)
    }

    @Test
    public fun `save + findById preserves Sold-state side data`() {
        val repo = PostgresMarketplaceRepository(db)
        val id = ListingId(UUID.randomUUID())
        val seller = PlayerId(UUID.randomUUID())
        val buyer = PlayerId(UUID.randomUUID())
        val soldAt = Instant.parse("2026-05-19T11:00:00Z")
        val listing = MarketplaceListing(
            id = id,
            seller = seller,
            itemId = ItemId("example-gun"),
            price = ShopPrice(Currency.GOLD, 25L),
            listedAt = Instant.parse("2026-05-19T10:00:00Z"),
            status = ListingStatus.Sold(buyer = buyer, soldAt = soldAt),
        )
        repo.save(listing)
        val status = repo.findById(id)!!.status
        assertThat(status).isInstanceOf(ListingStatus.Sold::class.java)
        status as ListingStatus.Sold
        assertThat(status.buyer).isEqualTo(buyer)
        assertThat(status.soldAt).isEqualTo(soldAt)
    }

    @Test
    public fun `findActiveBySeller filters to active rows for one seller`() {
        val repo = PostgresMarketplaceRepository(db)
        val seller = PlayerId(UUID.randomUUID())
        val otherSeller = PlayerId(UUID.randomUUID())
        val activeId = ListingId(UUID.randomUUID())
        val cancelledId = ListingId(UUID.randomUUID())
        val otherSellerActiveId = ListingId(UUID.randomUUID())
        val item = ItemId("example-sword")
        val price = ShopPrice(Currency.GOLD, 100L)
        val now = Instant.parse("2026-05-19T10:00:00Z")
        repo.save(MarketplaceListing(activeId, seller, item, price, now, ListingStatus.Active))
        repo.save(MarketplaceListing(cancelledId, seller, item, price, now, ListingStatus.Cancelled))
        repo.save(
            MarketplaceListing(otherSellerActiveId, otherSeller, item, price, now, ListingStatus.Active),
        )

        val mine = repo.findActiveBySeller(seller)
        assertThat(mine.map { it.id }).containsExactly(activeId)
    }

    @Test
    public fun `findAllActive returns every active row across sellers`() {
        val repo = PostgresMarketplaceRepository(db)
        val s1 = PlayerId(UUID.randomUUID())
        val s2 = PlayerId(UUID.randomUUID())
        val a1 = ListingId(UUID.randomUUID())
        val a2 = ListingId(UUID.randomUUID())
        val cancelled = ListingId(UUID.randomUUID())
        val item = ItemId("example-sword")
        val price = ShopPrice(Currency.GOLD, 50L)
        val now = Instant.parse("2026-05-19T10:00:00Z")
        repo.save(MarketplaceListing(a1, s1, item, price, now, ListingStatus.Active))
        repo.save(MarketplaceListing(a2, s2, item, price, now, ListingStatus.Active))
        repo.save(MarketplaceListing(cancelled, s1, item, price, now, ListingStatus.Cancelled))

        val active = repo.findAllActive().map { it.id }.toSet()
        assertThat(active).contains(a1, a2)
        assertThat(active).doesNotContain(cancelled)
    }

    @Test
    public fun `save upserts on id — status transition rewrites the row`() {
        val repo = PostgresMarketplaceRepository(db)
        val id = ListingId(UUID.randomUUID())
        val seller = PlayerId(UUID.randomUUID())
        val buyer = PlayerId(UUID.randomUUID())
        val item = ItemId("example-sword")
        val price = ShopPrice(Currency.GOLD, 100L)
        val now = Instant.parse("2026-05-19T10:00:00Z")
        repo.save(MarketplaceListing(id, seller, item, price, now, ListingStatus.Active))
        val soldAt = Instant.parse("2026-05-19T12:00:00Z")
        repo.save(
            MarketplaceListing(
                id = id,
                seller = seller,
                itemId = item,
                price = price,
                listedAt = now,
                status = ListingStatus.Sold(buyer = buyer, soldAt = soldAt),
            ),
        )
        val finalListing = repo.findById(id)
        assertThat(finalListing).isNotNull
        assertThat(finalListing!!.status).isInstanceOf(ListingStatus.Sold::class.java)
        assertThat(repo.findActiveBySeller(seller)).isEmpty()
    }
}
