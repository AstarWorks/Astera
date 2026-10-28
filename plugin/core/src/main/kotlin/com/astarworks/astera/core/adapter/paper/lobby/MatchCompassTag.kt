package com.astarworks.astera.core.adapter.paper.lobby

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * PDC marker of the match navigation compass (hotbar slot index 8, `HotbarLayout.MATCH_COMPASS_SLOT`). The item is
 * locked in place by the match plugin and never enters an inventory snapshot (the serializer skips tagged stacks).
 */
public object MatchCompassTag {
    private val KEY: NamespacedKey = NamespacedKey.fromString("astera:match_compass")!!

    public fun isCompass(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty || !stack.hasItemMeta()) return false
        return stack.itemMeta?.persistentDataContainer?.has(KEY, PersistentDataType.BYTE) == true
    }

    public fun mark(stack: ItemStack) {
        stack.editMeta { it.persistentDataContainer.set(KEY, PersistentDataType.BYTE, 1) }
    }
}
