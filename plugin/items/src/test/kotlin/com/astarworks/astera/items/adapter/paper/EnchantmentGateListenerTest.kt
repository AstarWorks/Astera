package com.astarworks.astera.items.adapter.paper

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.bukkit.NamespacedKey
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Test

class EnchantmentGateListenerTest {
    private val plugin = mockk<Plugin> { every { name } returns "Astera-Items" }
    private val key = NamespacedKey(plugin, PaperItemMaterializer.KEEP_ON_DEATH_KEY)

    private fun stack(keep: Boolean): ItemStack {
        val pdc = mockk<PersistentDataContainer> {
            every { get(key, PersistentDataType.BYTE) } returns if (keep) 1.toByte() else null
        }
        val meta = mockk<ItemMeta> { every { persistentDataContainer } returns pdc }
        return mockk<ItemStack> { every { itemMeta } returns meta }
    }

    @Test
    fun `kept stacks are retained by Paper and removed from world drops`() {
        val kept = stack(true)
        val dropped = stack(false)
        val drops = mutableListOf(kept, dropped)
        val retained = mutableListOf<ItemStack>()
        val event = mockk<PlayerDeathEvent> {
            every { keepInventory } returns false
            every { getDrops() } returns drops
            every { itemsToKeep } returns retained
        }

        EnchantmentGateListener(plugin).onPlayerDeath(event)

        assertThat(drops).containsExactly(dropped)
        assertThat(retained).containsExactly(kept)
    }

    @Test
    fun `keep inventory does not retain a second copy`() {
        val event = mockk<PlayerDeathEvent> { every { keepInventory } returns true }
        EnchantmentGateListener(plugin).onPlayerDeath(event)
    }
}
