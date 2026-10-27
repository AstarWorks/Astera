package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.adapter.paper.item.PaperItemMaterializer
import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.domain.item.ItemSpec
import com.astarworks.astera.core.application.port.outbound.item.DeliveryResult
import com.astarworks.astera.core.application.port.outbound.item.IItemDelivery
import com.astarworks.astera.core.application.port.outbound.item.ItemOrigin
import com.astarworks.astera.core.domain.player.PlayerId
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
    private val idResolver: PaperItemIdResolver,
) : IItemDelivery {

    override fun refreshHeld(items: IItemRegistry): Int {
        materializer.rescanModels()
        var changed = 0
        for (player in server.onlinePlayers) {
            for (stack in player.inventory.contents) {
                if (stack == null) continue
                val id = idResolver.itemIdOf(stack) ?: continue
                val spec = items.find(id) ?: continue
                if (materializer.refreshVisuals(stack, spec, PlayerId(player.uniqueId))) changed++
            }
        }
        return changed
    }

    override fun give(target: IMcPlayer, spec: ItemSpec, amount: Int, origin: ItemOrigin): DeliveryResult {
        val bukkitPlayer = server.getPlayer(target.id.uuid)
            ?: return DeliveryResult.InventoryFull(target.name)

        val stack = materializer.build(spec, target, amount)
        if (origin == ItemOrigin.TRAINING) TrainingItemTag.mark(stack, spec.id.value)
        if (origin == ItemOrigin.EQUIP_IF_FREE && equipIfFree(bukkitPlayer, spec, stack)) return DeliveryResult.Delivered
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

    /** Puts a single armour [stack] into the [spec]'s armour slot when that slot is empty. */
    private fun equipIfFree(player: org.bukkit.entity.Player, spec: ItemSpec, stack: org.bukkit.inventory.ItemStack): Boolean {
        val inv = player.inventory
        val current = when (spec.equipSlot) {
            com.astarworks.astera.core.domain.item.EquipSlot.HELMET -> inv.helmet
            com.astarworks.astera.core.domain.item.EquipSlot.CHESTPLATE -> inv.chestplate
            com.astarworks.astera.core.domain.item.EquipSlot.LEGGINGS -> inv.leggings
            com.astarworks.astera.core.domain.item.EquipSlot.BOOTS -> inv.boots
            else -> return false
        }
        if (current != null && !current.type.isAir) return false
        when (spec.equipSlot) {
            com.astarworks.astera.core.domain.item.EquipSlot.HELMET -> inv.helmet = stack
            com.astarworks.astera.core.domain.item.EquipSlot.CHESTPLATE -> inv.chestplate = stack
            com.astarworks.astera.core.domain.item.EquipSlot.LEGGINGS -> inv.leggings = stack
            else -> inv.boots = stack
        }
        return true
    }
}
