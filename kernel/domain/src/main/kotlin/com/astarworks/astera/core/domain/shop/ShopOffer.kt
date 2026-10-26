package com.astarworks.astera.core.domain.shop

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.item.ItemId

/**
 * One purchasable line item inside a [ShopSpec].
 *
 * Resolved via `IItemRegistry` (returns [com.astarworks.astera.core.domain.item.ItemSpec])
 * — Phase 1 PoC focuses on weapons authored under `content/items/`; future
 * tiers cover any item with skills. The purchase use case looks up the spec,
 * converts to an adapter template, and hands it to `IMcPlayer.giveItem`.
 *
 * @property id Stable id unique within the parent [ShopSpec].
 * @property itemId Reference into `content/items/`.
 * @property price What the buyer pays.
 * @property stock How many are available.
 * @property displayKey Optional i18n key for a friendly offer name (falls
 *   back to the item's own display name when null).
 * @property limit Ownership rule: unlock-once (default) or repeatable consumable (ADR-0211).
 * @property category Optional tab the offer is listed under (`weapon`, `armor`, ...); null = uncategorised.
 */
public data class ShopOffer(
    public val id: ShopOfferId,
    public val itemId: ItemId,
    public val price: ShopPrice,
    public val stock: ShopStock,
    public val displayKey: MessageKey? = null,
    public val limit: PurchaseLimit = PurchaseLimit.ONCE,
    public val category: String? = null,
    /** Pinned first with a badge for a player who has bought nothing here yet (the newcomer's first buy). */
    public val recommended: Boolean = false,
)
