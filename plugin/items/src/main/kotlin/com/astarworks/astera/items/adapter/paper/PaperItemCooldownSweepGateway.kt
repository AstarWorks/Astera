package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.domain.cooldown.ItemCooldownSweep
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemCooldownSweepGateway
import org.bukkit.NamespacedKey
import org.bukkit.Server

/**
 * Paper concrete of [IItemCooldownSweepGateway] (ADR-0195): sets the vanilla
 * cooldown of the item's `use_cooldown` group, so the client draws the grey
 * sweep over every slot holding it. [PaperItemMaterializer] writes the same
 * group key onto the stack.
 */
public class PaperItemCooldownSweepGateway(private val server: Server) : IItemCooldownSweepGateway {

    override fun sweep(playerId: PlayerId, itemId: ItemId, ticks: Int) {
        val player = server.getPlayer(playerId.uuid) ?: return
        val group = groupOf(itemId) ?: return
        player.setCooldown(group, ticks.coerceAtLeast(0))
    }

    public companion object {
        /** The `use_cooldown` group of [itemId]. */
        public fun groupOf(itemId: ItemId): NamespacedKey? = NamespacedKey.fromString(ItemCooldownSweep.groupKey(itemId))
    }
}
