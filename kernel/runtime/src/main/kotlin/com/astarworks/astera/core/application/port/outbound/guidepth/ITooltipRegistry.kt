package com.astarworks.astera.core.application.port.outbound.guidepth

import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.guidepth.TooltipScope
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: registry of [ITooltipExtensionProvider]s indexed by
 * [TooltipScope] (Wave 5gui-depth, ADR-0114).
 *
 * Feature plugins call `register(scope, provider)` at `onEnable`; the GUI
 * adapter (Wave 6) calls `lookup(item, viewer)` on every tooltip render to
 * collect the aggregated lines.
 *
 * # Per-item categorisation
 *
 * The registry needs to know an item's rarity / category to evaluate
 * [TooltipScope.ByRarity] / [TooltipScope.ByCategory] matches. Both are
 * looked up via the injected [rarityOf] / [categoriesOf] resolvers (passed
 * at construction in the in-memory impl) so the registry stays
 * vendor-neutral.
 *
 * # Ordering
 *
 * The aggregated `lookup` result preserves registration order across
 * matching providers — first registered, first rendered. Feature plugins
 * register at `onEnable`; the order is deterministic because plugin enable
 * order is deterministic.
 */
public interface ITooltipRegistry {

    /**
     * Register a [provider] to fire for every item matching [scope]. The returned [Registration] takes it out again
     * (a plugin rewire, ADR-0311, retires its providers this way).
     */
    public fun register(scope: TooltipScope, provider: ITooltipExtensionProvider): Registration

    public fun interface Registration {
        public fun unregister()
    }

    /**
     * Compute the aggregated tooltip-line extensions for [item] as seen by
     * [viewer]. Invokes every registered provider whose scope matches.
     * Empty list if no provider matches.
     */
    public fun lookup(item: ItemId, viewer: PlayerId): List<TooltipLine>

    /**
     * Lookup function for an item's [Rarity] — used internally to evaluate
     * [TooltipScope.ByRarity]. Returns `null` if the item is unknown to the
     * resolver; unknown items are treated as "no rarity-scoped providers
     * apply".
     *
     * Exposed on the interface so consumers (debug UI, Wave 6 adapter) can
     * inspect what the registry sees.
     */
    public fun rarityOf(item: ItemId): Rarity?

    /**
     * Lookup function for an item's categories — used internally to
     * evaluate [TooltipScope.ByCategory]. Returns an empty list if the item
     * has no recognised categories.
     */
    public fun categoriesOf(item: ItemId): List<com.astarworks.astera.core.domain.guidepth.ItemCategory>
}
