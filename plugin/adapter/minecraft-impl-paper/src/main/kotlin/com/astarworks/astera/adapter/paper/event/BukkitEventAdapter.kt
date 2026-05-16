package com.astarworks.astera.adapter.paper.event

import com.astarworks.astera.adapter.minecraftapi.event.IMcEvent
import com.astarworks.astera.adapter.paper.entity.PaperPlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent

/**
 * Bukkit Listener that translates Paper events into vendor-neutral
 * [IMcEvent] instances and delegates to a sink.
 *
 * The sink is a plain lambda so application-layer use cases never have to
 * implement Bukkit's Listener interface.
 */
class BukkitEventAdapter(
    private val sink: (IMcEvent) -> Unit,
) : Listener {

    @EventHandler
    fun onInteract(e: PlayerInteractEvent) {
        when (e.action) {
            Action.LEFT_CLICK_AIR -> sink(IMcEvent.PlayerLeftClickAir(PaperPlayer(e.player)))
            Action.LEFT_CLICK_BLOCK -> sink(IMcEvent.PlayerLeftClickBlock(PaperPlayer(e.player)))
            else -> Unit
        }
    }
}
