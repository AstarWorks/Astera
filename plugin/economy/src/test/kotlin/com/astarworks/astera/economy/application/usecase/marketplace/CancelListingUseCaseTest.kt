package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [CancelListingUseCase] (RTM #12 / ADR-0035).
 * Reuses [InMemoryMarketplaceRepository] from [CreateListingUseCaseTest].
 */
public class CancelListingUseCaseTest {

    private val seller = PlayerId(UUID.randomUUID())
    private val stranger = PlayerId(UUID.randomUUID())
    private val listingId = ListingId(UUID.fromString("00000000-0000-0000-0000-000000000020"))
    private val listedAt = Instant.parse("2026-05-19T10:00:00Z")

    private val marketplace = InMemoryMarketplaceRepository()
    private val eventBus = FakeDomainEventBus()
    private val uc = CancelListingUseCase(marketplace = marketplace, eventBus = eventBus)

    private fun seed(status: ListingStatus = ListingStatus.Active): MarketplaceListing {
        val listing = MarketplaceListing(
            id = listingId,
            seller = seller,
            itemId = ItemId("example-sword"),
            price = ShopPrice(Currency.GOLD, 100L),
            listedAt = listedAt,
            status = status,
        )
        marketplace.save(listing)
        return listing
    }

    @Test
    public fun `seller cancels active listing — status becomes Cancelled`() {
        seed()
        val result = uc.execute(seller, listingId)
        assertThat(result.isSuccess).isTrue
        assertThat(marketplace.findById(listingId)!!.status).isEqualTo(ListingStatus.Cancelled)
    }

    @Test
    public fun `unknown listing yields NotFound`() {
        val result = uc.execute(seller, listingId)
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(CancelListingError.NotFound(listingId))
    }

    @Test
    public fun `non-seller cancel attempt yields NotSeller`() {
        seed()
        val result = uc.execute(stranger, listingId)
        assertThat(result.isFailure).isTrue
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(CancelListingError.NotSeller::class.java)
        // Listing untouched.
        assertThat(marketplace.findById(listingId)!!.status).isEqualTo(ListingStatus.Active)
    }

    @Test
    public fun `already-cancelled listing yields NotActive`() {
        seed(status = ListingStatus.Cancelled)
        val result = uc.execute(seller, listingId)
        assertThat(result.isFailure).isTrue
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(CancelListingError.NotActive::class.java)
    }
}
