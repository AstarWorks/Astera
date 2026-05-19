package com.astarworks.astera.economy.application.usecase.marketplace

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IMarketplaceRepository
import com.astarworks.astera.economy.application.service.CreatorRevenueCalculator
import com.astarworks.astera.economy.domain.marketplace.ListingId
import com.astarworks.astera.economy.domain.marketplace.ListingStatus
import java.time.Instant

/**
 * Atomically purchases an [com.astarworks.astera.economy.domain.marketplace.ListingStatus.Active]
 * marketplace listing (RTM #12, ADR-0035).
 *
 * Pipeline:
 *  1. Resolve the listing, verify it is [ListingStatus.Active] and the buyer
 *     is not the seller.
 *  2. Debit the buyer's wallet for the full listing price.
 *  3. Compute the (`seller`, `creator`, `platform`) revenue split via
 *     [CreatorRevenueCalculator.split] (Worker γ, ADR-0035).
 *  4. Credit the seller and (if any) creator wallet from the split. The
 *     platform amount is implicitly "burned" — kept off-ledger in Phase 1 PoC.
 *  5. Save the listing with status [ListingStatus.Sold].
 *
 * Phase 1 PoC: the `eventBus` dependency is held but not yet published from
 * — a `ListingSold` event is reserved for Phase C wiring once a typed
 * `DomainEvent` variant lands. Item escrow / handoff to the buyer's
 * inventory is similarly deferred to Wave 6 when the inventory adapter ships.
 */
public class PurchaseListingUseCase(
    private val marketplace: IMarketplaceRepository,
    private val ledger: ICurrencyLedger,
    private val revenueCalc: CreatorRevenueCalculator,
    @Suppress("unused") private val eventBus: IDomainEventBus,
    private val clock: () -> Instant = Instant::now,
) {

    public fun execute(
        buyer: PlayerId,
        listingId: ListingId,
    ): Result<Unit, PurchaseListingError> {
        val listing = marketplace.findById(listingId)
            ?: return Result.failure(PurchaseListingError.NotFound(listingId))

        if (listing.status !is ListingStatus.Active) {
            return Result.failure(
                PurchaseListingError.NotActive(listingId, listing.status),
            )
        }
        if (buyer == listing.seller) {
            return Result.failure(PurchaseListingError.SelfPurchase)
        }

        val totalPrice = listing.price.amount
        val currency = listing.price.currency

        val debit = ledger.debit(buyer, currency, totalPrice)
        if (debit is Result.Failure) {
            return when (val err = debit.error) {
                is WalletError.InsufficientFunds -> Result.failure(
                    PurchaseListingError.InsufficientFunds(
                        currency = err.currency,
                        balance = err.have,
                        needed = err.tried,
                    ),
                )
            }
        }

        val split = revenueCalc.split(listing.itemId, totalPrice)
        if (split.sellerAmount > 0) {
            ledger.credit(listing.seller, currency, split.sellerAmount)
        }
        val creatorId = split.creatorId
        val creatorAmount = split.creatorAmount
        if (creatorId != null && creatorAmount != null && creatorAmount > 0) {
            ledger.credit(creatorId, currency, creatorAmount)
        }
        // split.platformAmount is intentionally not credited anywhere in
        // Phase 1 PoC — it is "burned" off-ledger. A platform vault account
        // is reserved for Phase 4 when the marketplace fee policy lands.

        marketplace.save(
            listing.copy(
                status = ListingStatus.Sold(buyer = buyer, soldAt = clock()),
            ),
        )
        return Result.success(Unit)
    }
}

/** Expected failure modes of [PurchaseListingUseCase.execute]. */
public sealed class PurchaseListingError {

    public abstract val messageKey: MessageKey

    public data class NotFound(val id: ListingId) : PurchaseListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.not_found")
    }

    public data class NotActive(
        val id: ListingId,
        val status: ListingStatus,
    ) : PurchaseListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.not_active")
    }

    public data object SelfPurchase : PurchaseListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.self_purchase")
    }

    public data class InsufficientFunds(
        val currency: Currency,
        val balance: Long,
        val needed: Long,
    ) : PurchaseListingError() {
        override val messageKey: MessageKey =
            MessageKey("astera.economy.marketplace.error.insufficient_funds")
    }
}
