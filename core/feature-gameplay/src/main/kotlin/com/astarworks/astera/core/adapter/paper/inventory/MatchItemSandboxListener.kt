package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Keeps the per-match inventory snapshot (ADR-0198) honest: while the entry snapshot of a running
 * match is held for a player, the player cannot throw items on the ground.
 *
 * The match-end restore rewrites the inventory with the entry snapshot. An item dropped during the
 * match is therefore still in the snapshot - leaving the dropped entity pickable after the match
 * (or by a teammate who then keeps it) would duplicate it. Pickups are not blocked: objective
 * items (carried TNT, wool, jewelry) are picked up through the same Bukkit event.
 *
 * Runs at LOW so Items' drop-key reload trigger (NORMAL, not `ignoreCancelled`) still fires.
 */
public class MatchItemSandboxListener(
    private val isHeld: (PlayerId) -> Boolean,
) : Listener {

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public fun onDrop(event: PlayerDropItemEvent) {
        if (isHeld(PlayerId(event.player.uniqueId))) event.isCancelled = true
    }
}
