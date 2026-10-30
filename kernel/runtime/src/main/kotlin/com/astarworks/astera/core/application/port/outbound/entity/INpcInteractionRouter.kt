package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.NpcInteractionClick
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: maps NPC handle + click → [GuiIntent].
 *
 * Any NPC can be bound to any GuiIntent — shop (`lobby.shop.open`), loadout
 * edit, tutorial step, tournament queue, etc. The shop NPC is just an NPC in
 * `content/lobby-npcs/` whose right click is bound to `lobby.shop.open`
 * (WP13 removed the shop-specific `IShopVendor` binding).
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

    /** Remember that [player] dispatched a click bound on [handle] (so a despawn can close what that click opened). */
    public fun recordUse(player: PlayerId, handle: EntityHandle)

    /** Players who dispatched a bound click on [handle] since it was bound. */
    public fun usersOf(handle: EntityHandle): Set<PlayerId>

    /** Remove all bindings (and recorded users) for [handle]. Called by the spawner on despawn. */
    public fun unbind(handle: EntityHandle)

    /** Test-only / admin: snapshot of every current binding. */
    public fun all(): Map<Pair<EntityHandle, NpcInteractionClick>, GuiIntent>
}
