package com.astarworks.astera.items.platform.paper

import com.astarworks.astera.core.adapter.paper.event.PaperClickEventBridge
import com.astarworks.astera.items.adapter.paper.EnchantmentGateListener
import com.astarworks.astera.items.platform.wiring.ItemsWiringResult
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper-only half of Items' enable (ADR-0174): Bukkit listener registration.
 * The simulator replaces this by feeding `IMcEvent`s to
 * [ItemsWiringResult.triggerListener] directly.
 */
internal object ItemsPaperBindings {

    fun register(plugin: JavaPlugin, wired: ItemsWiringResult) {
        // Click → trigger sink (vendor-neutral listener behind a Paper bridge).
        val clickBridge = PaperClickEventBridge { event -> wired.triggerListener.handle(event) }
        plugin.server.pluginManager.registerEvents(clickBridge, plugin)

        // Enchant-gate + keep-on-death listener (Bukkit-native).
        plugin.server.pluginManager.registerEvents(EnchantmentGateListener(plugin), plugin)
    }
}
