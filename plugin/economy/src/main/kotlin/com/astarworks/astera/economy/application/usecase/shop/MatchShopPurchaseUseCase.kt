package com.astarworks.astera.economy.application.usecase.shop

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.IOwnedItemStock
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.matchshop.MatchShopOffer

/**
 * Buys one [MatchShopOffer] of the in-match ore shop: the price is paid in ore items taken out of the buyer's
 * inventory (all of them or nothing), the item is handed over, and armour goes straight into its free armour slot.
 * A weapon upgrade ([MatchShopOffer.upgradeFrom]) additionally hands in the carried lower tier, so the buyer ends up with
 * exactly one weapon of that ladder. Works only for a player who is in a running match; the lobby GOLD shop ([ShopPurchaseUseCase]) stays blocked there.
 */
public class MatchShopPurchaseUseCase(
    private val itemRegistry: IItemRegistry,
    private val server: IMcServer,
    private val stock: () -> IOwnedItemStock?,
    private val delivery: () -> IItemDelivery?,
    /** `true` while [PlayerId] is playing a match. */
    private val inMatch: (PlayerId) -> Boolean,
) {
    public fun execute(playerId: PlayerId, offer: MatchShopOffer): Result<MatchShopReceipt, MatchShopError> {
        val player = server.findPlayer(playerId) ?: return Result.failure(MatchShopError.PlayerNotOnline)
        if (!inMatch(playerId)) return Result.failure(MatchShopError.NotInMatch)
        val spec = itemRegistry.find(offer.itemId)
            ?: return Result.failure(MatchShopError.DeliveryFailed("Item '${offer.itemId.value}' is not registered"))
        val items = delivery() ?: return Result.failure(MatchShopError.DeliveryFailed("Item delivery is not available"))
        val owned = stock() ?: return Result.failure(MatchShopError.DeliveryFailed("Inventory access is not available"))

        val base = offer.upgradeFrom
        if (base != null && owned.count(playerId, base) < 1) return Result.failure(MatchShopError.MissingBaseItem(base))
        val missing = offer.price.entries.firstOrNull { (ore, count) -> owned.count(playerId, ore) < count }
        if (missing != null) {
            return Result.failure(MatchShopError.NotEnoughOre(missing.key, missing.value, owned.count(playerId, missing.key)))
        }
        val taken = mutableListOf<Map.Entry<ItemId, Int>>()
        for (entry in offer.price.entries) {
            if (!owned.remove(playerId, entry.key, entry.value)) {
                taken.forEach { refund(items, player, it) }
                return Result.failure(MatchShopError.NotEnoughOre(entry.key, entry.value, owned.count(playerId, entry.key)))
            }
            taken += entry
        }
        if (base != null && !owned.remove(playerId, base, 1)) {
            taken.forEach { refund(items, player, it) }
            return Result.failure(MatchShopError.MissingBaseItem(base))
        }
        return when (val given = items.give(player, spec, offer.stack, ItemOrigin.EQUIP_IF_FREE)) {
            is DeliveryResult.Delivered, is DeliveryResult.InventoryFull -> Result.success(MatchShopReceipt(offer.itemId, offer.stack, offer.price))
            is DeliveryResult.UnknownTemplate -> {
                taken.forEach { refund(items, player, it) }
                base?.let { itemRegistry.find(it) }?.let { items.give(player, it, 1) }
                Result.failure(MatchShopError.DeliveryFailed("Item template '${given.templateKey}' cannot be materialized"))
            }
        }
    }

    private fun refund(items: IItemDelivery, player: com.astarworks.astera.core.adapter.api.entity.IMcPlayer, paid: Map.Entry<ItemId, Int>) {
        val ore = itemRegistry.find(paid.key) ?: return
        items.give(player, ore, paid.value)
    }
}

/** What a successful purchase handed over and charged. */
public data class MatchShopReceipt(val itemId: ItemId, val amount: Int, val paid: Map<ItemId, Int>)

/** Expected failure modes of [MatchShopPurchaseUseCase.execute]. */
public sealed class MatchShopError {
    public data object PlayerNotOnline : MatchShopError()

    /** The in-match shop sells only to players in a running match. */
    public data object NotInMatch : MatchShopError()

    /** [ore] fell short: [need] required, [have] carried. */
    public data class NotEnoughOre(public val ore: ItemId, public val need: Int, public val have: Int) : MatchShopError()

    /** An upgrade needs [item] in the inventory and the buyer carries none. */
    public data class MissingBaseItem(public val item: ItemId) : MatchShopError()

    public data class DeliveryFailed(public val detail: String) : MatchShopError()
}
