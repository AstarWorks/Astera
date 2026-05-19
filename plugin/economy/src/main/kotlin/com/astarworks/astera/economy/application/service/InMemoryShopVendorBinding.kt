package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.port.outbound.IShopVendor
import com.astarworks.astera.economy.application.port.outbound.VendorId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of [IShopVendor] used as the Wave 5g priority-0
 * default (ADR-0071).
 *
 * Mirrors the shape of [InMemoryShopRegistry]: a plain [ConcurrentHashMap]
 * keyed by [VendorId], populated either at startup (e.g. a YAML loader binding
 * configured entity UUIDs to shops) or at runtime by an admin command.
 *
 * # Thread safety
 *
 * Backing store is concurrent so the Paper main thread's interact-event
 * lookup is safe against an off-thread admin `/astera-economy vendor bind`
 * mutating the map. Replacement semantics: [bind] is last-write-wins per
 * the [IShopVendor.bind] contract.
 *
 * Phase 4 (Wave 8b) ships a `PaperVillagerShopVendor` /
 * `CitizensShopVendor` priority-100 overlay; the in-memory port stays as the
 * cross-environment default (tests, headless smoke).
 */
public class InMemoryShopVendorBinding : IShopVendor {

    private val bindings: ConcurrentHashMap<VendorId, ShopId> = ConcurrentHashMap()

    override fun bind(vendorId: VendorId, shopId: ShopId) {
        bindings[vendorId] = shopId
    }

    override fun lookup(vendorId: VendorId): ShopId? = bindings[vendorId]

    override fun unbind(vendorId: VendorId) {
        bindings.remove(vendorId)
    }

    override fun all(): Map<VendorId, ShopId> = bindings.toMap()

    /** Drop every binding. Reserved for Phase 4 hot-reload / test reset. */
    public fun clear() {
        bindings.clear()
    }
}
