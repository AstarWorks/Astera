package com.astarworks.astera.core.adapter.paper.item

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Central guarantee that no Astera stack in a player's hands shows vanilla metadata lines (UI-13). Every producer
 * goes through [PaperItemMaterializer], but stacks also arrive by paths that skip it (player data saved before the
 * `tooltip_display` existed, durability / enchant / command edits, item swaps), so this listener re-checks
 * ([PaperItemMaterializer.ensureTooltipHidden]) on join, on every player-inventory slot change and when a container
 * is opened. The check is idempotent and rewrites in place only when the display is missing or incomplete.
 */
public class PaperItemTooltipGuard(
    private val materializer: () -> PaperItemMaterializer?,
) : Listener {

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onJoin(event: PlayerJoinEvent) {
        sweep(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onSlotChange(event: PlayerInventorySlotChangeEvent) {
        val m = materializer() ?: return
        // The event carries a copy; the live slot (a mirror of the server stack) is what gets fixed.
        m.ensureTooltipHidden(event.player.inventory.getItem(event.slot))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onOpen(event: InventoryOpenEvent) {
        val m = materializer() ?: return
        event.inventory.contents.forEach { m.ensureTooltipHidden(it) }
        (event.player as? Player)?.let { sweep(it) }
    }

    private fun sweep(player: Player) {
        val m = materializer() ?: return
        player.inventory.contents.forEach { m.ensureTooltipHidden(it) }
    }
}
