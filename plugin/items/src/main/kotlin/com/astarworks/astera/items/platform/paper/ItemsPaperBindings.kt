package com.astarworks.astera.items.platform.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
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

    fun register(plugin: JavaPlugin, listeners: OwnedListeners, wired: ItemsWiringResult, rackFrames: PaperTrainingRackGateway, rangeWorld: () -> String?) {
        // Input → trigger sink (vendor-neutral listener behind a Paper bridge).
        val inputListener = PaperItemInputListener(
            itemIdResolver = PaperItemIdResolver(plugin, plugin.server),
            sink = wired.triggerListener::handle,
        )
        listeners.register(inputListener)

        // Hotbar selection -> active weapon (resource HUD / per-weapon capacity).
        listeners.register(
            PaperHeldItemListener(PaperItemIdResolver(plugin, plugin.server), wired.handleTrigger))

        // Enchant-gate + keep-on-death listener (Bukkit-native).
        listeners.register(EnchantmentGateListener(plugin))

        // F3-11: weapon rack interaction + lobby-only guarantees for its stacks.
        wired.trainingRack?.let { rack ->
            listeners.register(PaperTrainingRackListener(rack, rackFrames, rangeWorld))
        }
    }
}
