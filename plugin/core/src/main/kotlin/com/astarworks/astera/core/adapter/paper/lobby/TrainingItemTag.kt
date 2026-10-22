package com.astarworks.astera.core.adapter.paper.lobby

import org.bukkit.NamespacedKey
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * PDC marker on weapons taken from the lobby training rack (F3-11, ADR-0200). The value is the Astera
 * item id. Tagged stacks are lobby-only: [com.astarworks.astera.core.adapter.paper.inventory.PaperInventorySnapshotSerializer]
 * drops them from every snapshot (match entry, quit, handoff, vault), and Combat's guard strips them when the
 * player enters a match, changes world or quits.
 */
public object TrainingItemTag {
    private val KEY: NamespacedKey = NamespacedKey.fromString("astera:training_item")!!

    /** The Astera item id carried by [stack], or `null` for any other item. */
    public fun itemId(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty || !stack.hasItemMeta()) return null
        return stack.itemMeta?.persistentDataContainer?.get(KEY, PersistentDataType.STRING)
    }

    public fun isTraining(stack: ItemStack?): Boolean = itemId(stack) != null

    public fun mark(stack: ItemStack, itemId: String) {
        stack.editMeta { it.persistentDataContainer.set(KEY, PersistentDataType.STRING, itemId) }
    }

    /** Removes every training stack from [inventory]; returns how many slots were cleared. */
    public fun strip(inventory: Inventory): Int {
        var removed = 0
        for (i in 0 until inventory.size) {
            if (isTraining(inventory.getItem(i))) {
                inventory.setItem(i, null)
                removed++
            }
        }
        return removed
    }
}
