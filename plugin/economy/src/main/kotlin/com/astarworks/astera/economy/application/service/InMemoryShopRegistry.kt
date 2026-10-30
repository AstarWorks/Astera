package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.application.port.outbound.IShopRegistry
import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.core.domain.shop.ShopSpec
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [IShopRegistry] used by Wave 4.5 R3.5 (ADR-0027).
 *
 * Mirrors the shape of `InMemoryGuiRegistry` / `MutableWeaponRegistry`: a plain
 * [ConcurrentHashMap] keyed by [ShopId], populated at startup by
 * [ShopLoaderService] from `content/shops/` (yaml/toml/json) and optionally by
 * use cases that compose shops at runtime (Phase 4 web admin).
 *
 * # Thread safety
 *
 * Backing store is concurrent so the (rare) case of a use case registering a
 * dynamically-composed shop while a GUI click reads is safe. Lookups happen on
 * the Paper main thread for shop browse / purchase, but feature plugins may
 * register from any thread per ADR-0022 wiring guidance, so we don't downgrade
 * to a plain `HashMap`.
 *
 * # Replacement semantics
 *
 * `register` is last-write-wins per the [IShopRegistry.register] contract — the
 * loader's Phase 4 hot-reload story relies on this.
 */
public class InMemoryShopRegistry : IShopRegistry {

    private val specs: ConcurrentHashMap<ShopId, ShopSpec> = ConcurrentHashMap()

    override fun register(spec: ShopSpec) {
        specs[spec.id] = spec
    }

    override fun resolve(id: ShopId): ShopSpec? = specs[id]

    override fun all(): Collection<ShopSpec> = specs.values.toList()

    /** Reload: make the registry hold exactly [replacement] (stale ids are dropped). */
    public fun replaceAll(replacement: Collection<ShopSpec>) {
        val keep = replacement.map { it.id }.toSet()
        replacement.forEach { specs[it.id] = it }
        specs.keys.retainAll(keep)
    }

    /** Drop every registered entry. Reserved for Phase 4 hot-reload. */
    public fun clear() {
        specs.clear()
    }
}
