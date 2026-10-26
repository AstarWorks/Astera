package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.shop.PurchaseLimit

/** The cheapest shop unlock a player does not own yet, and how far their GOLD balance is from it. */
public data class NextUnlock(
    public val nameKey: MessageKey?,
    public val itemId: String,
    public val price: Long,
    public val balance: Long,
) {
    public val affordable: Boolean get() = balance >= price
    public val shortBy: Long get() = (price - balance).coerceAtLeast(0L)
}

/**
 * Picks the goal the post-match reward block points at: among the `limit: once` GOLD offers the player has
 * not bought, the cheapest one. Closes the loop match -> GOLD -> shop (docs/vision/areas/economy-life.md).
 * Returns null once everything is owned (or there is no shop).
 */
public class NextUnlockFinder(
    private val registry: () -> IShopRegistry?,
    private val purchases: () -> IShopPurchaseRepository?,
    private val ledger: ICurrencyLedger,
) {
    public fun find(playerId: PlayerId): NextUnlock? {
        val shops = registry()?.all() ?: return null
        val bought = purchases()
        val candidate = shops.asSequence()
            .flatMap { shop -> shop.offers.asSequence().map { shop to it } }
            .filter { (_, offer) -> offer.limit == PurchaseLimit.ONCE && offer.price.currency == Currency.GOLD }
            .filter { (shop, offer) -> bought == null || bought.count(playerId, shop.id, offer.id) == 0 }
            .minByOrNull { (_, offer) -> offer.price.amount } ?: return null
        val offer = candidate.second
        return NextUnlock(offer.displayKey, offer.itemId.value, offer.price.amount, ledger.balance(playerId, Currency.GOLD))
    }
}
