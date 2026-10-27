package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IWornItems
import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** Reads the Astera item id (PDC `item_id`) of the four armor slots of an online player. */
public class PaperWornItems(private val server: Server) : IWornItems {
    override fun wornItems(playerId: PlayerId): Map<ArmorSlot, ItemId> {
        val inv = server.getPlayer(playerId.uuid)?.inventory ?: return emptyMap()
        val out = HashMap<ArmorSlot, ItemId>()
        idOf(inv.helmet)?.let { out[ArmorSlot.HELMET] = it }
        idOf(inv.chestplate)?.let { out[ArmorSlot.CHESTPLATE] = it }
        idOf(inv.leggings)?.let { out[ArmorSlot.LEGGINGS] = it }
        idOf(inv.boots)?.let { out[ArmorSlot.BOOTS] = it }
        return out
    }

    private fun idOf(stack: ItemStack?): ItemId? {
        val pdc = stack?.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == ITEM_ID_KEY } ?: return null
        return pdc.get(key, PersistentDataType.STRING)?.let { runCatching { ItemId(it) }.getOrNull() }
    }

    private companion object {
        /** Mirrors `PaperItemIdResolver.ITEM_ID_KEY` in the items plugin (sibling plugins are not imported). */
        const val ITEM_ID_KEY = "item_id"
    }
}
