package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillItemGateway
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** `consume_item`: Astera items are found in the holder's inventory by their PDC `item_id` (v2 behaviour, kept 1:1). */
public class PaperSkillItemGateway(private val server: Server) : ISkillItemGateway {
    override fun consume(cast: CastRef, holder: UUID, item: String, count: Int): Boolean {
        val inv = server.getPlayer(holder)?.inventory ?: return false
        var left = count
        for (slot in 0 until inv.size) {
            if (left <= 0) break
            val stack = inv.getItem(slot) ?: continue
            if (idOf(stack) != item) continue
            val take = minOf(left, stack.amount)
            if (take >= stack.amount) inv.setItem(slot, null) else stack.amount -= take
            left -= take
        }
        return left <= 0
    }

    private fun idOf(stack: ItemStack): String? {
        val pdc = stack.itemMeta?.persistentDataContainer ?: return null
        val key = pdc.keys.firstOrNull { it.key == "item_id" } ?: return null
        return pdc.get(key, PersistentDataType.STRING)
    }
}
