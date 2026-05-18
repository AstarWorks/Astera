package com.astarworks.astera.weapons.adapter

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.item.IMcItemTemplate
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.weapons.application.port.outbound.DeliveryResult
import com.astarworks.astera.weapons.application.port.outbound.IWeaponDelivery
import com.astarworks.astera.weapons.domain.weapon.WeaponSpec

/**
 * Paper-side concrete of [IWeaponDelivery].
 *
 * Resolves the weapon's `itemId` through Core's `IItemRegistry`, hands the
 * resolved template off to [IMcPlayer.giveItem] for materialization (which
 * is where Bukkit `ItemStack` construction lives, in `PaperPlayer`), and
 * reports back a [DeliveryResult].
 *
 * Inventory-full reporting is best-effort: Bukkit's `Inventory.addItem`
 * returns leftovers when slots are exhausted, but `IMcPlayer.giveItem` does
 * not expose that surface yet. Phase 3 wires the happy path through cleanly;
 * a richer `InventoryFull` signal is a Phase 4 follow-up once a use case
 * actually needs to react to it (e.g. dropping the item at the player's feet
 * or refunding currency on a shop purchase).
 *
 * Per ADR-0002, Bukkit imports are sanctioned here (`weapons/adapter`
 * package); Konsist will fail the build if they leak into Weapons' domain or
 * application layers. Phase 3 reuses the existing `astera:item-id` PDC tag
 * written by `PaperPlayer.giveItem`; the Stage 2 H weapon-in-hand lookup
 * keys off it.
 */
public class PaperWeaponDelivery(
    private val itemRegistry: IItemRegistry<IMcItemTemplate>,
) : IWeaponDelivery {

    override fun give(target: IMcPlayer, weapon: WeaponSpec): DeliveryResult {
        val template = itemRegistry.find(weapon.itemId)
            ?: return DeliveryResult.UnknownItem(weapon.itemId.value)

        // Weapon-level display name / lore overrides take precedence over
        // the item template's defaults (typical: weapon name shows on a
        // sword, not the underlying item's vanilla name). PaperPlayer's
        // giveItem accepts MiniMessage-formatted strings; we pass MessageKey
        // values directly so the player's renderer resolves them per locale.
        // (Phase 3 keeps the override path minimal — full per-viewer
        // rendering of weapon names lives in the click listener / use case
        // once IMessageRenderer is plumbed through.)
        target.giveItem(template = template, displayNameOverride = null, loreOverride = emptyList())
        return DeliveryResult.Delivered
    }
}
