package com.astarworks.astera.core.adapter.paper.gui

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.domain.gui.GuiSpec
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * UI-06 - F (swap hands) during a match opens the in-match mini menu (`content/gui/match-menu.yaml`).
 *
 * The vanilla swap is cancelled for match participants only; outside a match this listener does nothing, so the
 * lobby kit ([com.astarworks.astera.core.adapter.paper.lobby.PaperLobbyMenuKit]) keeps F = main menu. Runs at
 * LOWEST so no later swap handler acts on a swap that never happens.
 */
public class PaperMatchMenuListener(
    private val inMatch: (PlayerId) -> Boolean,
    private val menuSpec: () -> GuiSpec?,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onSwapHands(e: PlayerSwapHandItemsEvent) {
        val id = PlayerId(e.player.uniqueId)
        if (!inMatch(id)) return
        e.isCancelled = true
        val spec = menuSpec() ?: return
        val player = mcServer.findPlayer(id) ?: return
        guiAdapter.open(player, spec)
    }
}
