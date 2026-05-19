package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopOffer
import com.astarworks.astera.core.domain.shop.ShopSpec
import com.astarworks.astera.core.domain.shop.ShopStock
import com.astarworks.astera.economy.application.port.outbound.IShopReferenceQuery
import com.astarworks.astera.economy.application.port.outbound.PublicShopOffer
import com.astarworks.astera.economy.application.port.outbound.PublicShopReference

/**
 * Default implementation of [IShopReferenceQuery] used by Wave 5g (ADR-0071).
 *
 * Wraps [IShopRegistry] and projects each [ShopSpec] / [ShopOffer] to its
 * public-safe DTO ([PublicShopReference] / [PublicShopOffer]).
 *
 * # What is intentionally NOT exposed
 *
 * - **Live stock counts** — only the *authored* stock shape from the spec is
 *   inspected to derive [PublicShopOffer.available]. The runtime decrement
 *   counter lives behind [com.astarworks.astera.core.application.port.outbound.IShopStockRepository]
 *   (Worker α territory) and is deliberately kept off the public read-side to
 *   prevent low-stock-arbitrage scraping (see [IShopReferenceQuery] KDoc).
 * - Per-player gating predicates, internal offer payload fields, raw price
 *   currency enums beyond the stringified [priceLabel] format.
 *
 * # priceLabel format
 *
 * `"<amount> <currency>"` (e.g. `"500 GOLD"`) — Wave 5g keeps the format
 * stable but plain; Phase 4 web admin / Discord bot can re-format on the
 * presentation side from `Currency` + `amount` if richer display is needed.
 * The default is intentionally simple so machine consumers can split on the
 * single space without parsing logic.
 */
public class DefaultShopReferenceQuery(
    private val registry: IShopRegistry,
) : IShopReferenceQuery {

    override fun resolvePublic(shopId: ShopId): PublicShopReference? =
        registry.resolve(shopId)?.let(::project)

    override fun allPublic(): Collection<PublicShopReference> =
        registry.all().map(::project)

    private fun project(spec: ShopSpec): PublicShopReference =
        PublicShopReference(
            id = spec.id,
            displayNameKey = spec.displayKey.value,
            offers = spec.offers.map(::projectOffer),
        )

    private fun projectOffer(offer: ShopOffer): PublicShopOffer =
        PublicShopOffer(
            offerId = offer.id.value,
            itemId = offer.itemId.value,
            priceLabel = "${offer.price.amount} ${offer.price.currency.name}",
            available = isAvailable(offer.stock),
        )

    /**
     * `true` iff the **authored** stock shape implies availability.
     *
     * Per the KDoc on this class: we deliberately do not consult
     * `IShopStockRepository` for the live decrement counter — the public read
     * side reflects "is this offer in principle available" not "are there
     * physical units left right now".
     */
    private fun isAvailable(stock: ShopStock): Boolean = when (stock) {
        is ShopStock.Unlimited -> true
        is ShopStock.Limited -> stock.remaining > 0
    }
}
