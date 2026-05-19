package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.application.service.CreatorRevenueCalculator
import com.astarworks.astera.economy.application.service.InMemoryCreatorRevenueRepository
import com.astarworks.astera.economy.application.service.InMemoryCurrencyLedger
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [PurchaseListingUseCase] (RTM #12 / ADR-0035).
 *
 * Drives [CreatorRevenueCalculator] (Worker γ) via a fake
 * [ICreatorRevenueRepository] so the (sellerAmount, creatorAmount,
 * platformAmount) split is observable without replicating γ's math here.
 * With `platformFeeRate = 0.20` and `salePrice = 100`, the calculator
 * produces `(80, null, null, 20)` when no creator record exists, and
 * `(remainingAfterFee * (1 - revenueShare), remainingAfterFee * revenueShare,
 * creatorId, 20)` otherwise.
 */
public class PurchaseListingUseCaseTest {

    private val seller = PlayerId(UUID.randomUUID())
    private val buyer = PlayerId(UUID.randomUUID())
    private val creator = PlayerId(UUID.randomUUID())
    private val item = ItemId("example-sword")
    private val listingId = ListingId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
    private val listedAt = Instant.parse("2026-05-19T10:00:00Z")
    private val soldAt = Instant.parse("2026-05-19T11:00:00Z")

    private val marketplace = InMemoryMarketplaceRepository()
    private val ledger = InMemoryCurrencyLedger()
    private val creatorRevenues = InMemoryCreatorRevenueRepository()
    private val revenueCalc = CreatorRevenueCalculator(creatorRevenues, platformFeeRate = 0.20)
    private val eventBus = FakeDomainEventBus()

    private val uc = PurchaseListingUseCase(
        marketplace = marketplace,
        ledger = ledger,
        revenueCalc = revenueCalc,
        eventBus = eventBus,
        clock = { soldAt },
    )

    private fun seedListing(
        price: Long = 100L,
        currency: Currency = Currency.GOLD,
        status: ListingStatus = ListingStatus.Active,
        sellerOverride: PlayerId = seller,
    ): MarketplaceListing {
        val listing = MarketplaceListing(
            id = listingId,
            seller = sellerOverride,
            itemId = item,
            price = ShopPrice(currency, price),
            listedAt = listedAt,
            status = status,
        )
        marketplace.save(listing)
        return listing
    }

    @Test
    public fun `happy path debits buyer credits seller and marks listing Sold`() {
        seedListing(price = 100L)
        ledger.credit(buyer, Currency.GOLD, 200L)

        val result = uc.execute(buyer, listingId)
        assertThat(result.isSuccess).isTrue

        // 20% platform fee on 100 -> seller gets 80, platform 20, no creator.
        assertThat(ledger.balance(buyer, Currency.GOLD)).isEqualTo(100L)
        assertThat(ledger.balance(seller, Currency.GOLD)).isEqualTo(80L)
        assertThat(ledger.balance(creator, Currency.GOLD)).isEqualTo(0L)

        val stored = marketplace.findById(listingId)!!
        val status = stored.status
        assertThat(status).isInstanceOf(ListingStatus.Sold::class.java)
        status as ListingStatus.Sold
        assertThat(status.buyer).isEqualTo(buyer)
        assertThat(status.soldAt).isEqualTo(soldAt)
    }

    @Test
    public fun `creator credit happens when a CreatorRevenue record exists`() {
        seedListing(price = 100L)
        ledger.credit(buyer, Currency.GOLD, 200L)
        creatorRevenues.save(
            CreatorRevenue(itemId = item, creatorId = creator, revenueShare = 0.50),
        )

        val result = uc.execute(buyer, listingId)
        assertThat(result.isSuccess).isTrue
        // platformAmount = 20, remainingAfterFee = 80,
        // creatorAmount = floor(80 * 0.5) = 40, sellerAmount = 80 - 40 = 40.
        assertThat(ledger.balance(seller, Currency.GOLD)).isEqualTo(40L)
        assertThat(ledger.balance(creator, Currency.GOLD)).isEqualTo(40L)
    }

    @Test
    public fun `listing not found yields NotFound`() {
        val result = uc.execute(buyer, listingId)
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(PurchaseListingError.NotFound(listingId))
    }

    @Test
    public fun `listing not Active yields NotActive`() {
        seedListing(status = ListingStatus.Cancelled)
        val result = uc.execute(buyer, listingId)
        assertThat(result.isFailure).isTrue
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(PurchaseListingError.NotActive::class.java)
    }

    @Test
    public fun `buyer == seller yields SelfPurchase`() {
        seedListing()
        val result = uc.execute(seller, listingId)
        assertThat(result.isFailure).isTrue
        assertThat(result.errorOrNull()).isEqualTo(PurchaseListingError.SelfPurchase)
        // Listing remains Active — no mutation.
        assertThat(marketplace.findById(listingId)!!.status).isEqualTo(ListingStatus.Active)
    }

    @Test
    public fun `insufficient funds yields InsufficientFunds and no ledger mutation`() {
        seedListing(price = 100L)
        ledger.credit(buyer, Currency.GOLD, 30L)

        val result = uc.execute(buyer, listingId)
        assertThat(result.isFailure).isTrue
        val err = result.errorOrNull()
        assertThat(err).isInstanceOf(PurchaseListingError.InsufficientFunds::class.java)

        // Buyer untouched, seller untouched, listing still Active.
        assertThat(ledger.balance(buyer, Currency.GOLD)).isEqualTo(30L)
        assertThat(ledger.balance(seller, Currency.GOLD)).isEqualTo(0L)
        assertThat(marketplace.findById(listingId)!!.status).isEqualTo(ListingStatus.Active)
    }
}

