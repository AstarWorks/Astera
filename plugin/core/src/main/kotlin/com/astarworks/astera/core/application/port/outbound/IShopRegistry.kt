package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopSpec

/**
 * Outbound port: look up declared [ShopSpec]s by [ShopId].
 *
 * Wave 4.5 (R3.5, ADR-0027): mirrors [IItemRegistry] / [IGuiRegistry]. Loaded
 * at plugin startup by `ShopLoaderService` (plugin/economy) from
 * `content/shops/`.
 *
 * Lives in `plugin/core` so the registry's `KClass` identity is consistent
 * across plugins. Phase 4 (Web admin) may register dynamic shops at runtime;
 * the port already supports that via [register].
 */
public interface IShopRegistry {
    public fun resolve(id: ShopId): ShopSpec?
    public fun register(spec: ShopSpec)
    public fun all(): Collection<ShopSpec>
}
