package com.astarworks.astera.core.adapter.paper.inventory

import com.astarworks.astera.core.application.service.handoff.PlayerHandoffService
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Bukkit `PlayerJoinEvent` / `PlayerQuitEvent` driver for [PlayerHandoffService]
 * (Wave 6b-3 ADR-0158; ordering and hand-off tokens since F3-6 ADR-0187).
 *
 * All logic - snapshot restore on join (after the hand-off token on a networked
 * server), save on quit, the `Loading` session lock that
 * [InventoryActionLockEnforcer] enforces - lives in the Bukkit-free service so
 * the headless simulator runs the same code. This class only forwards events.
 */
public class PlayerInventoryLifecycleListener(
    private val handoff: () -> PlayerHandoffService?,
) : Listener {

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public fun onPlayerJoin(event: PlayerJoinEvent) {
        handoff()?.onJoin(PlayerId(event.player.uniqueId))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onPlayerQuit(event: PlayerQuitEvent) {
        handoff()?.onQuit(PlayerId(event.player.uniqueId))
    }
}
