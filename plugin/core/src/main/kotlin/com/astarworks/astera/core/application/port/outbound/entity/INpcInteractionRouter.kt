package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.gui.GuiIntent

/**
 * Outbound port: maps NPC handle + click → [GuiIntent].
 *
 * Wave 5g introduced `IShopVendor` (VendorId → ShopId binding) for the
 * **shop-specific** case. This router generalises that pattern so any
 * NPC can be bound to any GuiIntent — shop, loadout edit, tutorial step,
 * tournament queue, etc.
 *
 * Per ADR-0082: Wave 5g's `IShopVendor` is intentionally kept as a thin
 * specialisation (shop intent only) rather than refactored to use this
 * router; the two coexist while the broader GuiIntent surface (Wave 5o)
 * stabilises. Once stable, `IShopVendor` becomes a derived view.
 *
 * # Click model
 *
 * Right and left click can be bound independently. The Paper interact
 * listener (Wave 6b) decodes Bukkit's `PlayerInteractEntityEvent` /
 * `PlayerAttackEntityEvent` into [NpcInteractionClick.RIGHT] /
 * [NpcInteractionClick.LEFT], looks up the corresponding intent here,
 * and dispatches via `IGuiIntentRouter` (Wave 5e).
 */
public interface INpcInteractionRouter {

    /** Bind a right-click intent on [handle]. Replaces any existing binding. */
    public fun bindRightClick(handle: EntityHandle, intent: GuiIntent)

    /** Bind a left-click intent on [handle]. Replaces any existing binding. */
    public fun bindLeftClick(handle: EntityHandle, intent: GuiIntent)

    /** Look up the bound intent, if any. */
    public fun resolve(handle: EntityHandle, click: NpcInteractionClick): GuiIntent?

    /** Remove all bindings for [handle]. Called by the spawner on despawn. */
    public fun unbind(handle: EntityHandle)

    /** Test-only / admin: snapshot of every current binding. */
    public fun all(): Map<Pair<EntityHandle, NpcInteractionClick>, GuiIntent>
}
