package com.astarworks.astera.items.application.usecase

import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemRenderer
import com.astarworks.astera.items.application.service.glow.InMemoryGlowProfileRegistry

/**
 * Refresh the glow schedule for a player's currently-held item
 * (Wave 5flex / ADR-0102 surface #1).
 *
 * Called by the Paper-side `PlayerItemHeldListener` (Phase C wiring)
 * whenever the player changes hotbar slots. Reads the item's [Rarity]
 * from the registry, resolves the per-rarity [GlowProfile], and asks
 * [IItemRenderer] to start (or cancel) the glow.
 *
 * # Invariants
 *
 * - Unknown item → cancel any prior glow. Items can be removed from
 *   `content/items/` between sessions; lingering glows from a missing
 *   item would be a memory leak.
 * - Inactive profile (Common) → cancel any prior glow.
 * - Active profile → start (replaces any prior schedule for the same
 *   (player, item) pair).
 */
public class ApplyHeldItemGlowUseCase(
    private val items: IItemRegistry,
    private val renderer: IItemRenderer,
    private val profiles: InMemoryGlowProfileRegistry,
) {

    public fun onItemHeld(player: PlayerId, newItemId: ItemId?, previousItemId: ItemId?) {
        // Cancel previous schedule (if any) before starting a new one,
        // so a hotbar swap from epic→common doesn't leak the epic glow.
        if (previousItemId != null && previousItemId != newItemId) {
            renderer.stopGlow(player, previousItemId)
        }

        if (newItemId == null) return
        val spec = items.find(newItemId) ?: run {
            renderer.stopGlow(player, newItemId)
            return
        }
        val profile = profiles.resolve(spec.rarity)
        renderer.startGlow(player, newItemId, profile)
    }
}
