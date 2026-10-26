package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.ICurrencyLedger
import com.astarworks.astera.core.application.port.outbound.IShopPurchaseRepository
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
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
    /** True when the goal is a cosmetic (bought in the cosmetics menu), false for a shop weapon / item. */
    public val cosmetic: Boolean = false,
) {
    public val affordable: Boolean get() = balance >= price
    public val shortBy: Long get() = (price - balance).coerceAtLeast(0L)
}

/**
 * Picks the goal the post-match reward block points at: among the `limit: once` GOLD offers the player has
 * not bought, the cheapest one. Closes the loop match -> GOLD -> shop (docs/vision/areas/economy-life.md).
 * Cosmetics (ADR-0269) join in only as an immediate goal: when the player cannot yet afford that weapon but can afford
 * an unowned sold cosmetic, the line points at the (most expensive) cosmetic they can buy right now, so the GOLD from a
 * short first match still has a visible use. Returns null once everything is owned (or there is no shop).
 */
public class NextUnlockFinder(
    private val registry: () -> IShopRegistry?,
    private val purchases: () -> IShopPurchaseRepository?,
    private val ledger: ICurrencyLedger,
    private val cosmetics: () -> CosmeticService? = { null },
) {
    public fun find(playerId: PlayerId): NextUnlock? {
        val shops = registry()?.all() ?: return null
        val bought = purchases()
        val balance = ledger.balance(playerId, Currency.GOLD)
        val candidate = shops.asSequence()
            .flatMap { shop -> shop.offers.asSequence().map { shop to it } }
            .filter { (_, offer) -> offer.limit == PurchaseLimit.ONCE && offer.price.currency == Currency.GOLD }
            .filter { (shop, offer) -> bought == null || bought.count(playerId, shop.id, offer.id) == 0 }
            .minByOrNull { (_, offer) -> offer.price.amount }
        val offer = candidate?.second
        if (offer == null || offer.price.amount > balance) {
            affordableCosmetic(playerId, balance)?.let { return it }
        }
        offer ?: return null
        return NextUnlock(offer.displayKey, offer.itemId.value, offer.price.amount, balance)
    }

    private fun affordableCosmetic(playerId: PlayerId, balance: Long): NextUnlock? {
        val service = cosmetics() ?: return null
        val pick = service.catalog()
            .filter { (it.priceGold ?: Long.MAX_VALUE) <= balance && !service.owns(playerId, it) }
            .maxWithOrNull(compareBy({ it.priceGold }, { it.id })) ?: return null
        return NextUnlock(pick.nameKey, pick.id, checkNotNull(pick.priceGold), balance, cosmetic = true)
    }
}
