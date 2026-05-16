package com.astarworks.astera.weapons.application.port.outbound

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Outbound port: deliver a weapon item to a player's inventory.
 *
 * ADR-0022 Phase 2-3: split out from the legacy
 * `IPlayerGateway.giveWeapon(...)` so Core stays weapon-agnostic. Core's
 * `IPlayerGateway` no longer references `WeaponSpec`; Weapons owns this
 * port and its Paper-side implementation
 * ([com.astarworks.astera.weapons.adapter.PaperWeaponDelivery]).
 *
 * Implementations resolve the weapon's [WeaponSpec.itemId] through
 * Core's `IItemRegistry<IMcItemTemplate>`, construct the platform-native
 * item (Bukkit `ItemStack` for Paper), apply the weapon's display name /
 * lore overrides, and place the item in the target's inventory.
 *
 * Failure surface: returns a [DeliveryResult] rather than throwing so use
 * cases can map each variant to a localized message key without try / catch.
 */
public interface IWeaponDelivery {
    public fun give(target: IMcPlayer, weapon: WeaponSpec): DeliveryResult
}

/** Outcome of a single [IWeaponDelivery.give] call. */
public sealed class DeliveryResult {

    /** Weapon item created and added to the target's inventory. */
    public data object Delivered : DeliveryResult()

    /**
     * Item was constructed but the target's inventory had no room. The
     * partial / leftover stack handling is implementation-defined; the
     * adapter typically drops the leftover at the player's feet and still
     * reports this variant so the caller can localize a hint.
     */
    public data class InventoryFull(val targetName: String) : DeliveryResult()

    /**
     * The weapon's `itemId` did not resolve through `IItemRegistry`
     * (typo in `content/weapons/<id>.yaml` or missing `content/items/<id>.yaml`).
     */
    public data class UnknownItem(val itemKey: String) : DeliveryResult()
}
