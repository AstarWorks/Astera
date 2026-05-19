package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus

/**
 * Seller-initiated cancellation of an
 * [com.astarworks.astera.economy.domain.marketplace.ListingStatus.Active]
 * listing (RTM #12, ADR-0035).
 *
 * Validates that the requesting player is the seller and that the listing is
 * still [ListingStatus.Active]. Already-terminal listings (Sold / Cancelled /
 * Expired) are rejected — re-cancelling a sold listing should be a NotActive
 * error, not a silent no-op, so the caller can surface the right message.
 */
public class CancelListingUseCase(
    private val marketplace: IMarketplaceRepository,
    @Suppress("unused") private val eventBus: IDomainEventBus,
) {

    public fun execute(
        requester: PlayerId,
        listingId: ListingId,
    ): Result<Unit, CancelListingError> {
        val listing = marketplace.findById(listingId)
            ?: return Result.failure(CancelListingError.NotFound(listingId))

        if (listing.seller != requester) {
            return Result.failure(
                CancelListingError.NotSeller(listingId, requester),
            )
        }
        if (listing.status !is ListingStatus.Active) {
            return Result.failure(
                CancelListingError.NotActive(listingId, listing.status),
            )
        }

        marketplace.save(listing.copy(status = ListingStatus.Cancelled))
        return Result.success(Unit)
    }
}

/** Expected failure modes of [CancelListingUseCase.execute]. */
public sealed class CancelListingError {

    public abstract val messageKey: MessageKey

    public data class NotFound(val id: ListingId) : CancelListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.not_found")
    }

    public data class NotSeller(
        val id: ListingId,
        val requester: PlayerId,
    ) : CancelListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.not_seller")
    }

    public data class NotActive(
        val id: ListingId,
        val status: ListingStatus,
    ) : CancelListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.not_active")
    }
}
