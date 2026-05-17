package com.astarworks.astera.core.domain.shop

import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * Declarative description of a shop: an ordered list of purchasable offers
 * plus a display title.
 *
 * Loaded at startup by `ShopLoaderService` (plugin/economy) from
 * `content/shops/`. Per ADR-0020 the type lives in `plugin/core` so the
 * registry's `KClass` identity is consistent across plugins.
 *
 * # Construction
 *
 * Always immutable. `copy(offers = ...)` is the standard mutation; for
 * runtime composition (Phase 4 web admin / dynamic refresh) the registry's
 * `register` is last-write-wins.
 *
 * # GUI rendering
 *
 * The GUI is **dynamic, not static-YAML**. `ShopBrowseUseCase` reads this
 * spec, iterates [offers], and constructs a `GuiSpec` at open-time with one
 * `Static` slot per offer carrying a `shop.purchase` `GuiIntent`. Stock /
 * price changes propagate without re-shipping any content YAML.
 */
public data class ShopSpec(
    public val id: ShopId,
    public val displayKey: MessageKey,
    public val offers: List<ShopOffer>,
) {
    init {
        val dupIds = offers.groupBy { it.id }.filter { it.value.size > 1 }.keys
        require(dupIds.isEmpty()) {
            "ShopSpec[${id.value}] has duplicate ShopOfferIds: $dupIds"
        }
    }

    /** Look up an offer by id; null if not present. */
    public fun offer(offerId: ShopOfferId): ShopOffer? =
        offers.firstOrNull { it.id == offerId }
}
