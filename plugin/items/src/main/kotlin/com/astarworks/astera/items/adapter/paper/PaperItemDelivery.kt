package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.core.adapter.paper.lobby.TrainingItemTag
import org.bukkit.Server

/**
 * Paper concrete of [IItemDelivery] (Wave _items-1, ADR-0073).
 *
 * Delegates the heavy lifting (material resolve, PDC, attributes, durability,
 * glint, lore i18n) to [PaperItemMaterializer], then drops the resulting
 * `ItemStack` into the player's inventory via Bukkit's
 * `PlayerInventory.addItem(stack)`. Leftover handling (inventory full) is
 * detected by inspecting the returned map of un-stored stacks.
 *
 * Per ADR-0002 + Konsist, Bukkit imports are sanctioned in `adapter/paper`.
 */
public class PaperItemDelivery(
    private val materializer: PaperItemMaterializer,
    private val server: Server,
) : IItemDelivery {

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
        val bukkitPlayer = server.getPlayer(target.id.uuid)
            ?: return DeliveryResult.InventoryFull(target.name)

        val stack = materializer.build(spec, target, amount)
        if (origin == ItemOrigin.TRAINING) TrainingItemTag.mark(stack, spec.id.value)
        val leftover = bukkitPlayer.inventory.addItem(stack)
        return if (leftover.isEmpty()) {
            DeliveryResult.Delivered
        } else {
            // Drop leftover at the player's feet so the item is not lost.
            leftover.values.forEach { remaining ->
                bukkitPlayer.world.dropItemNaturally(bukkitPlayer.location, remaining)
            }
            DeliveryResult.InventoryFull(target.name)
        }
    }
}
