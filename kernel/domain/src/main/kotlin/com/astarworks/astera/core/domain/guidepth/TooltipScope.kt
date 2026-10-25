package com.astarworks.astera.core.domain.guidepth

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity

/**
 * Predicate that decides whether an `ITooltipExtensionProvider` applies to a
 * given item (Wave 5gui-depth, ADR-0114).
 *
 * Used by `ITooltipRegistry.register(scope, provider)` to bucket providers so
 * `lookup(item, viewer)` only invokes the providers whose scopes match the
 * item. Sealed so the adapter / registry can dispatch exhaustively.
 *
 * # Variants
 *
 *  - [AllItems] — provider runs on every item (use sparingly; e.g., per-viewer
 *    debug overlay).
 *  - [ByRarity] — provider runs on items of the given [Rarity] (e.g., Wave
 *    5flex glow-rarity tooltip).
 *  - [ByCategory] — provider runs on items in the given [ItemCategory] (e.g.,
 *    Wave 5pve-survival gathering-tool tooltip).
 *  - [ByItem] — provider runs on exactly one [ItemId] (e.g., Wave 5refine
 *    refinement tier display for a specific weapon).
 */
public sealed interface TooltipScope {

    /** Matches every item. */
    public data object AllItems : TooltipScope

    /** Matches items with the given rarity. */
    public data class ByRarity(public val rarity: Rarity) : TooltipScope

    /** Matches items tagged with the given category. */
    public data class ByCategory(public val category: ItemCategory) : TooltipScope

    /** Matches exactly the given item id. */
    public data class ByItem(public val itemId: ItemId) : TooltipScope
}
