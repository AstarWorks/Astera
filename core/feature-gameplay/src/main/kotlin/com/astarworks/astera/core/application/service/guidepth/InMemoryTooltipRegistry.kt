package com.astarworks.astera.core.application.service.guidepth

import com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipExtensionProvider
import com.astarworks.astera.core.application.port.outbound.guidepth.ITooltipRegistry
import com.astarworks.astera.core.domain.guidepth.ItemCategory
import com.astarworks.astera.core.domain.guidepth.TooltipLine
import com.astarworks.astera.core.domain.guidepth.TooltipScope
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.item.Rarity
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default in-memory [ITooltipRegistry] (Wave 5gui-depth, ADR-0114).
 *
 * Wired by `AsteraCorePlugin` at priority 0. Wave 6 may add a packet-aware
 * impl at higher priority if Paper's tooltip render path needs adapter-side
 * caching, but most call sites should be happy with the in-memory version.
 *
 * # Rarity / category resolution
 *
 * The registry needs to know an item's rarity / categories to evaluate
 * [TooltipScope.ByRarity] / [TooltipScope.ByCategory]. Both are resolved via
 * the injected [rarityResolver] / [categoriesResolver] lambdas — passing the
 * `IItemRegistry` is the caller's responsibility (avoids a direct registry
 * dependency from this service, keeping it vendor-neutral and easy to fake).
 *
 * **Phase 1 default**: `AsteraCorePlugin` wires `rarityResolver = { null }`
 * and `categoriesResolver = { emptyList() }` until a Wave 5refine /
 * Wave 5flex / Wave 5pve-survival hookup lands. This means `ByRarity` /
 * `ByCategory` scopes never match in Phase 1 wiring — only `AllItems` /
 * `ByItem` providers fire. Feature plugins that need richer scoping must
 * either install a custom resolver or register with `ByItem`.
 *
 * # Thread-safety
 *
 * Provider lists are [CopyOnWriteArrayList] so the render path's `lookup`
 * never races against `register`. Registration is a slow path
 * (`onEnable`-time); lookup is a hot path (every tooltip render tick).
 */
public class InMemoryTooltipRegistry(
    private val rarityResolver: (ItemId) -> Rarity? = { null },
    private val categoriesResolver: (ItemId) -> List<ItemCategory> = { emptyList() },
) : ITooltipRegistry {

    private val allItemsProviders: CopyOnWriteArrayList<ITooltipExtensionProvider> =
        CopyOnWriteArrayList()
    private val byRarityProviders: MutableMap<Rarity, CopyOnWriteArrayList<ITooltipExtensionProvider>> =
        mutableMapOf()
    private val byCategoryProviders: MutableMap<ItemCategory, CopyOnWriteArrayList<ITooltipExtensionProvider>> =
        mutableMapOf()
    private val byItemProviders: MutableMap<ItemId, CopyOnWriteArrayList<ITooltipExtensionProvider>> =
        mutableMapOf()

    override fun register(scope: TooltipScope, provider: ITooltipExtensionProvider): ITooltipRegistry.Registration {
        val list = when (scope) {
            TooltipScope.AllItems -> allItemsProviders
            is TooltipScope.ByRarity -> byRarityProviders.getOrPut(scope.rarity) { CopyOnWriteArrayList() }
            is TooltipScope.ByCategory -> byCategoryProviders.getOrPut(scope.category) { CopyOnWriteArrayList() }
            is TooltipScope.ByItem -> byItemProviders.getOrPut(scope.itemId) { CopyOnWriteArrayList() }
        }
        list.add(provider)
        return ITooltipRegistry.Registration { list.remove(provider) }
    }

    override fun lookup(item: ItemId, viewer: PlayerId): List<TooltipLine> {
        val out = mutableListOf<TooltipLine>()
        // Registration-order across buckets: AllItems → ByRarity → ByCategory
        // → ByItem. Order inside a bucket is registration-order
        // (CopyOnWriteArrayList preserves insertion order).
        for (p in allItemsProviders) {
            out += p.extend(item, viewer)
        }
        val rarity = rarityResolver(item)
        if (rarity != null) {
            byRarityProviders[rarity]?.forEach { p -> out += p.extend(item, viewer) }
        }
        val categories = categoriesResolver(item)
        for (category in categories) {
            byCategoryProviders[category]?.forEach { p -> out += p.extend(item, viewer) }
        }
        byItemProviders[item]?.forEach { p -> out += p.extend(item, viewer) }
        return out
    }

    override fun rarityOf(item: ItemId): Rarity? = rarityResolver(item)

    override fun categoriesOf(item: ItemId): List<ItemCategory> = categoriesResolver(item)
}
