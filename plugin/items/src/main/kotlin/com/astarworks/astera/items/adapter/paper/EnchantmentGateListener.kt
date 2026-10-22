package com.astarworks.astera.items.adapter.paper

import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.PrepareItemEnchantEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * Enchant / death policy enforcement (Wave _items-1, ADR-0073).
 *
 * Two responsibilities, both driven by PDC tags written by
 * [PaperItemMaterializer]:
 *
 *  1. **Enchant gate** — cancel `PrepareItemEnchantEvent` if the item being
 *     enchanted has `astera:enchantable=0`. Designers opt items out of the
 *     vanilla enchanting table per [com.astarworks.astera.core.domain.item.ItemSpec.enchantable].
 *  2. **Keep on death** — move tagged stacks from the death drops into
 *     Paper's `itemsToKeep`, which restores them during respawn.
 *
 * Konsist allows Bukkit imports here (`adapter/paper`).
 */
public class EnchantmentGateListener(
    plugin: Plugin,
) : Listener {

    private val enchantableKey: NamespacedKey =
        NamespacedKey(plugin, PaperItemMaterializer.ENCHANTABLE_KEY)
    private val keepOnDeathKey: NamespacedKey =
        NamespacedKey(plugin, PaperItemMaterializer.KEEP_ON_DEATH_KEY)

    @EventHandler
    public fun onPrepareEnchant(event: PrepareItemEnchantEvent) {
        if (!isEnchantable(event.item)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    public fun onPlayerDeath(event: PlayerDeathEvent) {
        if (event.keepInventory) return
        val keepers = event.drops.filter { isKeepOnDeath(it) }
        if (keepers.isEmpty()) return
        event.itemsToKeep.addAll(keepers)
        event.drops.removeAll(keepers.toSet())
    }

    private fun isEnchantable(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return true // un-tagged stacks fall back to enchantable
        val value = meta.persistentDataContainer.get(enchantableKey, PersistentDataType.BYTE)
            ?: return true
        return value != 0.toByte()
    }

    private fun isKeepOnDeath(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        val value = meta.persistentDataContainer.get(keepOnDeathKey, PersistentDataType.BYTE)
            ?: return false
        return value != 0.toByte()
    }
}
