package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.shop.ShopId

/**
 * Outbound port: bind a "vendor entity" (NPC / villager / armor stand /
 * display entity / Citizens NPC) to a [ShopId] so right-clicking the entity
 * opens the shop GUI.
 *
 * Wave 5g (Core hardening) lands the **port commitment**; Paper impls
 * (`PaperVillagerShopVendor`, optional `CitizensShopVendor`) are scheduled
 * for Phase 4 (Wave 8b) per ADR-0071 — see RTM issue #4 which decided
 * **against** the WeaponMechanics built-in NPC-as-vendor approach.
 *
 * Wave 5g default: `InMemoryShopVendorBinding` (priority 0) — bindings live
 * in a `Map<VendorId, ShopId>` that the Paper listener consults when an
 * entity interact event fires.
 *
 * Vendor identity is opaque ([VendorId.value] is a plugin-defined string;
 * Paper impl typically embeds the entity UUID or NPC handle id) — the port
 * deliberately does not know what a "Bukkit entity" is (ADR-0002).
 */
public interface IShopVendor {

    /** Bind [shopId] to [vendorId]. Replaces any prior binding. */
    public fun bind(vendorId: VendorId, shopId: ShopId)

    /** Shop bound to [vendorId], or null if none. */
    public fun lookup(vendorId: VendorId): ShopId?

    /** Remove the binding for [vendorId]. No-op if absent. */
    public fun unbind(vendorId: VendorId)

    /** All current bindings. */
    public fun all(): Map<VendorId, ShopId>
}

/**
 * Vendor identity, opaque to the domain.
 *
 * Paper-side vendor adapters compose this from the entity's UUID (Villager,
 * ArmorStand, Display) or Citizens NPC handle id; the chat-alias / external
 * EC variants compose it from their own identifier space.
 */
@JvmInline
public value class VendorId(public val value: String) {
    init {
        require(value.isNotBlank()) { "VendorId must not be blank" }
    }

    override fun toString(): String = value
}
