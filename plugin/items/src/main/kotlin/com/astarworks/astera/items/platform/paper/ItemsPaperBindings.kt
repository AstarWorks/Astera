package com.astarworks.astera.items.platform.paper

import com.astarworks.astera.items.adapter.paper.EnchantmentGateListener
import com.astarworks.astera.items.adapter.paper.PaperItemIdResolver
import com.astarworks.astera.items.adapter.paper.PaperHeldItemListener
import com.astarworks.astera.items.adapter.paper.PaperItemInputListener
import com.astarworks.astera.items.adapter.paper.PaperTrainingRackGateway
import com.astarworks.astera.items.adapter.paper.PaperTrainingRackListener
import com.astarworks.astera.items.platform.wiring.ItemsWiringResult
import org.bukkit.plugin.java.JavaPlugin

/**
 * Paper-only half of Items' enable (ADR-0174): Bukkit listener registration.
 * The simulator replaces this by feeding `IMcEvent`s to
 * [ItemsWiringResult.triggerListener] directly.
 */
internal object ItemsPaperBindings {

    fun register(plugin: JavaPlugin, wired: ItemsWiringResult, rackFrames: PaperTrainingRackGateway, rangeWorld: () -> String?) {
        // Input → trigger sink (vendor-neutral listener behind a Paper bridge).
        val inputListener = PaperItemInputListener(
            itemIdResolver = PaperItemIdResolver(plugin, plugin.server),
            sink = wired.triggerListener::handle,
        )
        plugin.server.pluginManager.registerEvents(inputListener, plugin)

        // Hotbar selection -> active weapon (resource HUD / per-weapon capacity).
        plugin.server.pluginManager.registerEvents(
            PaperHeldItemListener(PaperItemIdResolver(plugin, plugin.server), wired.handleTrigger),
            plugin,
        )

        // Enchant-gate + keep-on-death listener (Bukkit-native).
        plugin.server.pluginManager.registerEvents(EnchantmentGateListener(plugin), plugin)

        // F3-11: weapon rack interaction + lobby-only guarantees for its stacks.
        wired.trainingRack?.let { rack ->
            plugin.server.pluginManager.registerEvents(PaperTrainingRackListener(rack, rackFrames, rangeWorld), plugin)
        }
    }
}
