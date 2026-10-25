package com.astarworks.astera.core.domain.anticheat

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId

/**
 * A discrete interaction that the anti-cheat surface samples for rate /
 * sanity validation. Fed to
 * [com.astarworks.astera.core.application.port.outbound.anticheat.IInteractValidator.validate].
 *
 * Held inside the domain so feature plugins can publish their own
 * interactions without depending on Bukkit event types.
 */
public sealed interface InteractAction {

    /**
     * Player opened a container (chest, barrel, custom GUI). Used to detect
     * chest-stuffer / inventory-bot signatures.
     */
    public data class ContainerOpen(val containerId: String) : InteractAction

    /** Player clicked a block. Tracks click-rate (auto-clicker detection). */
    public data class BlockClick(val position: Vec3) : InteractAction

    /** Player used an item (right-click). Tracks consumable / weapon swap spam. */
    public data class ItemUse(val itemId: ItemId) : InteractAction
}
