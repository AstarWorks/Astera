package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * ADR-0276: a cross-world teleport ejects the passengers of the caster, so a skill performance riding it would be left behind
 * as orphans. The performance ends in the same tick (death and quit arrive as domain events).
 */
public class PerformanceWorldChangeListener(
    private val endPerformances: (PlayerId) -> Unit,
) : Listener {

    public fun start(plugin: JavaPlugin) {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    @EventHandler
    public fun onWorldChange(event: PlayerChangedWorldEvent) {
        endPerformances(PlayerId(event.player.uniqueId))
    }
}
