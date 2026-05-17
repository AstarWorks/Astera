package com.astarworks.astera.core.domain.shop

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * One purchasable line item inside a [ShopSpec].
 *
 * Wave 4.5 limits the deliverable to vanilla [ItemId]s resolvable through
 * `IItemRegistry<IMcItemTemplate>` AND `IWeaponRegistry` — i.e. weapons only.
 * Armor / consumables / cosmetics need their own delivery ports first; the
 * purchase use case explicitly fails with `DeliveryFailed` for non-weapon ids.
 *
 * @property id Stable id unique within the parent [ShopSpec].
 * @property itemId Reference into `content/items/` and `content/weapons/`.
 *   In Wave 4.5 this MUST be a weapon id.
 * @property price What the buyer pays.
 * @property stock How many are available.
 * @property displayKey Optional i18n key for a friendly offer name (falls
 *   back to the item's own display name when null).
 */
public data class ShopOffer(
    public val id: ShopOfferId,
    public val itemId: ItemId,
    public val price: ShopPrice,
    public val stock: ShopStock,
    public val displayKey: MessageKey? = null,
)
