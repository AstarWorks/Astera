package com.astarworks.astera.core.adapter.paper.admin

import com.astarworks.astera.core.adapter.paper.lobby.MenuKitTag
import com.astarworks.astera.core.application.admin.panel.AdminPanelGuard
import com.astarworks.astera.core.application.admin.panel.OpenAdminPanelUseCase
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * Command-free entry to the admin panel (ADR-0233): sneak + swap-hands (F) opens the hub for
 * operators and holders of `astera.admin.panel`. Nothing is added to the hotbar. Anyone else keeps
 * the vanilla swap, and a menu-kit item in either hand keeps its own handling, so the lobby kit
 * is unaffected. The guard lookups are suppliers because the panel is wired after the listener
 * can be registered.
 */
public class PaperAdminQuickAccessListener(
    private val guard: () -> AdminPanelGuard?,
    private val open: () -> OpenAdminPanelUseCase?,
) : Listener {

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public fun onSwap(event: PlayerSwapHandItemsEvent) {
        val player = event.player
        if (!player.isSneaking) return
        if (MenuKitTag.isKit(event.mainHandItem) || MenuKitTag.isKit(event.offHandItem)) return
        val id = PlayerId(player.uniqueId)
        if (guard()?.canUse(id) != true) return
        val useCase = open() ?: return
        event.isCancelled = true
        useCase.execute(id)
    }
}
