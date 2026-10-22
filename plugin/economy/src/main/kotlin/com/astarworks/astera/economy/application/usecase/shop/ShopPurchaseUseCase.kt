package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.IShopStockRepository
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopPrice
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.core.domain.shop.ShopStockError
import com.astarworks.astera.core.domain.event.CurrencyChanged

/**
 * Atomically purchases one [com.astarworks.astera.core.domain.shop.ShopOffer]:
 * checks stock, debits the buyer's wallet, delivers the item to the player's
 * inventory, and publishes [CurrencyChanged].
 *
 * Wave 4.5 R3.5 use case (ADR-0027). Driven from the GUI by
 * [com.astarworks.astera.economy.application.intent.ShopIntentHandler] when a
 * `shop.purchase` [com.astarworks.astera.core.domain.gui.GuiIntent] fires.
 *
 * ## Architectural note (Konsist-compliant delivery)
 *
 * The use case delivers through the **vendor-neutral** chain (Wave _items-1 /
 * ADR-0073 unified items + weapons under `:plugin:items`):
 *
 *   `IItemRegistry.find(itemId)` (returns `ItemSpec`) →
 *   `IItemDelivery.give(player, spec)` (Items materializer; PDC item id, skills, attributes)
 *
 * `IItemRegistry` is non-generic; the Items plugin (renamed from Weapons by
 * ADR-0073) seeds it from `content/items/<id>.yaml` at startup. If Phase 4
 * needs item-class-specific overrides (cosmetic stack count, soulbound PDC
 * tag, etc.), Items can publish a vendor-neutral `IItemDelivery` port to
 * core and this use case swaps in that port without changing its public
 * contract.
 *
 * ## Steps
 *
 * 1. Player online check ([IMcServer.findPlayer]).
 * 2. Shop lookup ([IShopRegistry.resolve]).
 * 3. Offer lookup ([com.astarworks.astera.core.domain.shop.ShopSpec.offer]).
 * 4. Stock pre-check via the spec (`Limited.remaining > 0`) — a fast path
 *    to short-circuit obviously-sold-out offers before charging the
 *    wallet. The authoritative serialised check is step 7.
 * 5. Item template lookup ([IItemRegistry.find]).
 * 6. Debit via [ICurrencyLedger.debit].
 * 7. **Authoritative stock decrement** via
 *    [IShopStockRepository.decrementStock] — for [ShopStock.Limited]
 *    offers this is the atomic gate two concurrent buyers race for.
 *    On [ShopStockError.OutOfStock] / [ShopStockError.UnknownOffer] /
 *    [ShopStockError.Storage] the debit is reversed with
 *    [ICurrencyLedger.credit] and the use case fails.
 *    [ShopStock.Unlimited] offers skip the decrement.
 * 8. Deliver via [IItemDelivery.give].
 * 9. Publish [CurrencyChanged] with reason [CurrencyChangeReason.ShopPurchase].
 *
 * ## Why the dual stock check
 *
 * Step 4 reads the **spec**'s static `ShopStock.Limited.remaining` (the
 * YAML-authored initial); step 7 reads the **stock repository**'s
 * mutable per-offer count. The spec read is a fast user-facing
 * short-circuit so a sold-out offer doesn't even attempt a debit; the
 * repository decrement is the atomic gate that serialises concurrent
 * buyers. Without step 7, two buyers racing on the last unit would both
 * pass step 4 and both receive the item — the Wave 5g bug this use case
 * exists to fix.
 */
public class ShopPurchaseUseCase(
    private val registry: IShopRegistry,
    private val ledger: ICurrencyLedger,
    private val itemRegistry: IItemRegistry,
    private val messages: IMessageRenderer,
    private val server: IMcServer,
    private val eventBus: IDomainEventBus,
    private val stockRepository: IShopStockRepository,
    /** Items-owned materializer (skill bindings, attributes, durability, lore). Resolved lazily: Items enables after Economy. */
    private val delivery: () -> IItemDelivery?,
    /** `true` while a running match holds the buyer's entry snapshot (purchases are refused then). */
    private val inMatch: (PlayerId) -> Boolean,
) {
    public fun execute(
        playerId: PlayerId,
        shopId: ShopId,
        offerId: ShopOfferId,
    ): Result<ShopPurchaseReceipt, ShopPurchaseError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(ShopPurchaseError.PlayerNotOnline)
        if (inMatch(playerId)) return Result.failure(ShopPurchaseError.BlockedInMatch)

        val shopSpec = registry.resolve(shopId)
            ?: return Result.failure(ShopPurchaseError.ShopNotFound(shopId))

        val offer = shopSpec.offer(offerId)
            ?: return Result.failure(ShopPurchaseError.OfferNotFound(shopId, offerId))

        when (val stock = offer.stock) {
            is ShopStock.Limited -> if (stock.remaining <= 0) {
                return Result.failure(ShopPurchaseError.OutOfStock(shopId, offerId))
            }
            is ShopStock.Unlimited -> Unit
        }

        val spec = itemRegistry.find(offer.itemId)
            ?: return Result.failure(
                ShopPurchaseError.DeliveryFailed(
                    "Item '${offer.itemId.value}' is not registered (offer=${offerId.value}, shop=${shopId.value})",
                ),
            )
        // Delivery goes through the Items materializer so a bought weapon carries its
        // `astera-items:item_id` tag, skill bindings and attributes (the generic template path
        // wrote a different PDC key and produced an inert item). Checked BEFORE the debit.
        val itemDelivery = delivery()
            ?: return Result.failure(ShopPurchaseError.DeliveryFailed("Item delivery is not available on this server"))

        val debit = ledger.debit(playerId, offer.price.currency, offer.price.amount)
        return when (debit) {
            is Result.Failure -> when (debit.error) {
                is WalletError.InsufficientFunds ->
                    Result.failure(ShopPurchaseError.InsufficientFunds(offer.price.currency))
            }
            is Result.Success -> {
                // Authoritative stock gate. Unlimited offers skip this entirely
                // (no row was ever seeded; currentStock would return null).
                if (offer.stock is ShopStock.Limited) {
                    when (val decrement = stockRepository.decrementStock(shopId, offerId)) {
                        is Result.Failure -> {
                            // Reverse the debit — the buyer did not get the
                            // item, so they must not be charged. credit() is
                            // total (no failure variant) per the
                            // ICurrencyLedger contract.
                            ledger.credit(playerId, offer.price.currency, offer.price.amount)
                            return when (val stockErr = decrement.error) {
                                is ShopStockError.OutOfStock ->
                                    Result.failure(ShopPurchaseError.OutOfStock(shopId, offerId))
                                is ShopStockError.UnknownOffer ->
                                    // The spec advertised Limited(N) but no
                                    // stock row exists — operator misconfig
                                    // (loader did not seed). Surface as a
                                    // delivery failure so the player gets a
                                    // visible error.
                                    Result.failure(
                                        ShopPurchaseError.DeliveryFailed(
                                            "Stock row missing for offer='${offerId.value}' in shop='${shopId.value}' " +
                                                "(spec says Limited but no seeded row). Operator must re-run loader.",
                                        ),
                                    )
                                is ShopStockError.Storage ->
                                    Result.failure(
                                        ShopPurchaseError.DeliveryFailed(
                                            "Stock backend error: ${stockErr.detail}",
                                        ),
                                    )
                            }
                        }
                        is Result.Success -> Unit
                    }
                }

                // Deliver. InventoryFull still delivered (the leftover is dropped at the buyer's feet).
                when (val delivered = itemDelivery.give(player, spec, 1)) {
                    is DeliveryResult.Delivered, is DeliveryResult.InventoryFull -> Unit
                    is DeliveryResult.UnknownTemplate -> {
                        ledger.credit(playerId, offer.price.currency, offer.price.amount)
                        return Result.failure(
                            ShopPurchaseError.DeliveryFailed("Item template '${delivered.templateKey}' cannot be materialized"),
                        )
                    }
                }

                val newBalance = debit.value.balance(offer.price.currency)
                eventBus.publish(
                    CurrencyChanged(
                        playerId = playerId,
                        currency = offer.price.currency,
                        delta = -offer.price.amount,
                        newBalance = newBalance,
                        reason = CurrencyChangeReason.ShopPurchase(shopId, offerId),
                    ),
                )
                Result.success(
                    ShopPurchaseReceipt(
                        itemName = messages.renderText(playerId, spec.displayName),
                        price = offer.price,
                        newBalance = newBalance,
                    ),
                )
            }
        }
    }
}

/** Expected failure modes of [ShopPurchaseUseCase.execute]. */
public sealed class ShopPurchaseError {

    /** The target [PlayerId] has no online session on this server. */
    public data object PlayerNotOnline : ShopPurchaseError()

    /** No [com.astarworks.astera.core.domain.shop.ShopSpec] is registered under [shopId]. */
    public data class ShopNotFound(public val shopId: ShopId) : ShopPurchaseError()

    /**
     * The buyer is inside a running match. Their inventory is rewritten from the entry snapshot when
     * the match ends (ADR-0198), so anything bought now would be lost together with the currency.
     */
    public data object BlockedInMatch : ShopPurchaseError()

    /** The shop exists but has no offer with [offerId]. */
    public data class OfferNotFound(
        public val shopId: ShopId,
        public val offerId: ShopOfferId,
    ) : ShopPurchaseError()

    /** The offer is [ShopStock.Limited] with `remaining <= 0`. */
    public data class OutOfStock(
        public val shopId: ShopId,
        public val offerId: ShopOfferId,
    ) : ShopPurchaseError()

    /** The buyer lacks the required currency for this offer's price. */
    /** Wallet could not cover the price; [currency] is the one that fell short. */
    public data class InsufficientFunds(public val currency: Currency) : ShopPurchaseError()

    /**
     * The offer's item could not be delivered. Carries a human-readable
     * [reason] that the intent handler / command executor may log; user-facing
     * text is resolved through `astera.economy.shop.delivery_failed`.
     */
    public data class DeliveryFailed(public val reason: String) : ShopPurchaseError()
}

/** What a successful purchase tells the buyer: the item (rendered in their locale), what it cost, what is left. */
public data class ShopPurchaseReceipt(
    public val itemName: String,
    public val price: ShopPrice,
    public val newBalance: Long,
)
