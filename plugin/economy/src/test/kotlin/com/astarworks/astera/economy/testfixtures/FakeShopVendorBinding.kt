package com.astarworks.astera.economy.testfixtures

import com.astarworks.astera.core.domain.shop.ShopId
import com.astarworks.astera.economy.application.port.outbound.IShopVendor
import com.astarworks.astera.economy.application.port.outbound.VendorId

/**
 * In-memory [IShopVendor] for use case tests (ADR-0071 Wave 5g).
 *
 * Backed by a `VendorId → ShopId` map. Tests set up bindings by calling
 * [bind] for each scenario participant, then exercise the use case under
 * test. Vendors not bound return `null` from [lookup] — matching the port
 * contract "no shop is bound to this vendor entity".
 *
 * Lives in `:plugin:economy`'s test source (not `:plugin:test-fixtures`)
 * because [IShopVendor] is an economy-feature port; test-fixtures is
 * Core-only per ADR-0022 isolation.
 *
 * The Phase 4 (Wave 8b) `PaperVillagerShopVendor` / `CitizensShopVendor`
 * priority-100 overlay supersedes this for real deployments.
 */
public class FakeShopVendorBinding : IShopVendor {

    private val bindings: MutableMap<VendorId, ShopId> = linkedMapOf()

    override fun bind(vendorId: VendorId, shopId: ShopId) {
        bindings[vendorId] = shopId
    }

    override fun lookup(vendorId: VendorId): ShopId? = bindings[vendorId]

    override fun unbind(vendorId: VendorId) {
        bindings.remove(vendorId)
    }

    override fun all(): Map<VendorId, ShopId> = bindings.toMap()

    /** Drop every binding. Useful between scenarios in a shared harness. */
    public fun clear() {
        bindings.clear()
    }
}
