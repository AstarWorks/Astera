package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IGeneratorItemDrop
import org.bukkit.Location
import org.bukkit.Server

/**
 * Drops an Astera item (built by Items' [PaperItemMaterializer], so it carries its item id and match tag) as an ordinary
 * item entity. The materializer is resolved lazily: Items registers it when it enables, after Match's wiring runs.
 */
public class PaperGeneratorItemDrop(
    private val server: Server,
    private val items: () -> IItemRegistry?,
    private val materializer: () -> PaperItemMaterializer?,
) : IGeneratorItemDrop {
    override fun drop(world: WorldId, position: Vec3, itemId: String, amount: Int): Boolean {
        val w = server.getWorld(world.value) ?: return false
        val spec = runCatching { items()?.find(ItemId(itemId)) }.getOrNull() ?: return false
        val stack = materializer()?.buildFor(spec, null, amount.coerceAtLeast(1)) ?: return false
        w.dropItemNaturally(Location(w, position.x, position.y, position.z), stack)
        return true
    }
}
