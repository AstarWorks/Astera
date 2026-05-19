package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import com.astarworks.astera.economy.domain.marketplace.MarketplaceListing
import java.time.Instant
import java.util.UUID

/**
 * Creates a new player-authored marketplace listing (RTM #12, ADR-0035).
 *
 * Validates that the [itemId] is a known template and that the [price] is
 * strictly positive (zero is rejected here even though `ShopPrice` itself
 * allows it — a free marketplace listing makes no sense). The seller
 * ownership check is currently a structural placeholder: Phase 1 PoC trusts
 * the caller because the inventory adapter that would actually move the item
 * into escrow lands in Wave 6. The [eventBus] dependency is held but not yet
 * published from; a `ListingCreated` event is reserved for Phase C wiring.
 */
public class CreateListingUseCase(
    private val marketplace: IMarketplaceRepository,
    private val items: IItemRegistry,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    private val clock: () -> Instant = Instant::now,
    private val idGen: () -> UUID = UUID::randomUUID,
) {

    public fun execute(
        seller: PlayerId,
        itemId: ItemId,
        price: ShopPrice,
    ): Result<ListingId, CreateListingError> {
        if (items.find(itemId) == null) {
            return Result.failure(CreateListingError.UnknownItem(itemId))
        }
        if (price.amount <= 0) {
            return Result.failure(CreateListingError.InvalidPrice(price.amount))
        }
        // TODO(wave-5f orchestrator): real ownership check after Wave 6 inventory adapter
        val id = ListingId(idGen())
        val listing = MarketplaceListing(
            id = id,
            seller = seller,
            itemId = itemId,
            price = price,
            listedAt = clock(),
            status = ListingStatus.Active,
        )
        marketplace.save(listing)
        // TODO(wave-5f orchestrator): publish ListingCreated event once a
        // typed DomainEvent variant is added to core (Phase C decision).
        return Result.success(id)
    }
}

/** Expected failure modes of [CreateListingUseCase.execute]. */
public sealed class CreateListingError {

    public abstract val messageKey: MessageKey

    public data class UnknownItem(val itemId: ItemId) : CreateListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.unknown_item")
    }

    public data class InvalidPrice(val amount: Long) : CreateListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.invalid_price")
    }
}
