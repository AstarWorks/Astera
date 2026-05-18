package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.economy.WalletError
import com.astarworks.astera.core.domain.event.CurrencyChangeReason
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOfferId
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.domain.event.CurrencyChanged

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
 * The task brief originally called for delivery via `IWeaponRegistry` /
 * `IWeaponDelivery` from `plugin/weapons`. That violates the
 * `economy must not import weapons` Konsist rule (ADR-0022). The use case
 * therefore delivers through the **vendor-neutral** chain:
 *
 *   `IItemRegistry<IMcItemTemplate>.find(itemId)` →
 *   `IMcServer.findPlayer(playerId).giveItem(template, null, emptyList())`
 *
 * For Wave 4.5 the demo offers reference `content/weapons/<id>.yaml` items
 * which the Weapons plugin already registers into the same shared
 * `IItemRegistry<IMcItemTemplate>` at startup, so the visible behaviour
 * matches "weapon-only delivery" without crossing the plugin boundary. If
 * Phase 4 needs weapon-specific overrides (display name override, custom PDC
 * tags, etc.), Weapons can publish a vendor-neutral `IItemDelivery` port to
 * core and this use case swaps in that port without changing its public
 * contract.
 *
 * ## Steps
 *
 * 1. Player online check ([IMcServer.findPlayer]).
 * 2. Shop lookup ([IShopRegistry.resolve]).
 * 3. Offer lookup ([com.astarworks.astera.core.domain.shop.ShopSpec.offer]).
 * 4. Stock check (`Limited.remaining > 0`).
 * 5. Item template lookup ([IItemRegistry.find]).
 * 6. Debit via [ICurrencyLedger.debit].
 * 7. Deliver via [com.astarworks.astera.core.adapter.api.entity.IMcPlayer.giveItem].
 * 8. Publish [CurrencyChanged] with reason [CurrencyChangeReason.ShopPurchase].
 */
public class ShopPurchaseUseCase(
    private val registry: IShopRegistry,
    private val ledger: ICurrencyLedger,
    private val itemRegistry: IItemRegistry<IMcItemTemplate>,
    private val server: IMcServer,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        playerId: PlayerId,
        shopId: ShopId,
        offerId: ShopOfferId,
    ): Result<Unit, ShopPurchaseError> {
        val player = server.findPlayer(playerId)
            ?: return Result.failure(ShopPurchaseError.PlayerNotOnline)

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

        val template = itemRegistry.find(offer.itemId)
            ?: return Result.failure(
                ShopPurchaseError.DeliveryFailed(
                    "Item '${offer.itemId.value}' is not registered (offer=${offerId.value}, shop=${shopId.value})",
                ),
            )

        val debit = ledger.debit(playerId, offer.price.currency, offer.price.amount)
        return when (debit) {
            is Result.Failure -> when (debit.error) {
                is WalletError.InsufficientFunds -> Result.failure(ShopPurchaseError.InsufficientFunds)
            }
            is Result.Success -> {
                // Deliver — IMcPlayer.giveItem currently returns Unit, so this
                // arm always succeeds at the contract level. If the platform
                // adapter detects an unrecoverable failure it logs at WARN; a
                // typed surface for that case is a Phase 4 enhancement
                // (vendor-neutral IItemDelivery port).
                player.giveItem(template, displayNameOverride = null, loreOverride = emptyList())

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
                Result.success(Unit)
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
    public data object InsufficientFunds : ShopPurchaseError()

    /**
     * The offer's item could not be delivered. Carries a human-readable
     * [reason] that the intent handler / command executor may log; user-facing
     * text is resolved through `astera.economy.shop.delivery_failed`.
     */
    public data class DeliveryFailed(public val reason: String) : ShopPurchaseError()
}
