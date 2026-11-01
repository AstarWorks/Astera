package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.StarActionService
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

/** A completed block break feeds the star economy (ADR-0318); the service decides by material list and match whether it pays. */
public class PaperStarBlockListener(
    private val actions: () -> StarActionService?,
) : Listener {
    public fun start(plugin: org.bukkit.plugin.java.JavaPlugin, listeners: OwnedListeners) {
        listeners.register(this)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onBreak(event: BlockBreakEvent) {
        actions()?.onBlockBroken(PlayerId(event.player.uniqueId), event.block.type.name)
    }
}
