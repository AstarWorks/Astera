package com.astarworks.astera.economy.application.service.validation

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.application.port.outbound.validation.ContentReferenceCheck
import com.astarworks.astera.core.domain.validation.ContentValidationError

/**
 * Wave 5server-harden / ADR-0123: every [com.astarworks.astera.core.domain.shop.ShopOffer.itemId]
 * cited by a registered [com.astarworks.astera.core.domain.shop.ShopSpec] must
 * resolve to an entry in [IItemRegistry].
 *
 * Wired by [com.astarworks.astera.economy.platform.AsteraEconomyPlugin] at
 * `onEnable` — Economy already softdepends on Items (well, on Core's
 * [IItemRegistry] surface), so the cross-plugin walk fits without breaking
 * the sibling-isolation matrix.
 */
public class ShopReferencesItemCheck(
    private val shops: IShopRegistry,
    private val items: IItemRegistry,
) : ContentReferenceCheck {

    override fun check(): List<ContentValidationError> {
        val errors = mutableListOf<ContentValidationError>()
        for (shop in shops.all()) {
            shop.offers.forEachIndexed { idx, offer ->
                if (items.find(offer.itemId) == null) {
                    errors += ContentValidationError.MissingReference(
                        sourceId = "shop:${shop.id.value}",
                        referenceKind = "item",
                        referencedId = offer.itemId.value,
                        field = "offers[$idx].itemId",
                    )
                }
            }
        }
        return errors
    }
}
