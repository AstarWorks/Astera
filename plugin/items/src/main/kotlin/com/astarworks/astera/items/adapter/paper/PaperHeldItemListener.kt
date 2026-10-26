package com.astarworks.astera.items.adapter.paper

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.usecase.HandleItemTriggerUseCase
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/**
 * Reports the Astera item in the player's main hand to
 * [HandleItemTriggerUseCase.onHeldItemChanged] whenever the hotbar selection
 * changes (scroll / number key), on join and on an off-hand swap, so the
 * weapon-resource HUD follows what the player holds.
 */
public class PaperHeldItemListener(
    private val itemIdResolver: PaperItemIdResolver,
    private val handleTrigger: HandleItemTriggerUseCase,
) : Listener {

    @EventHandler
    public fun onHeld(e: PlayerItemHeldEvent) {
        val stack = e.player.inventory.getItem(e.newSlot)
        handleTrigger.onHeldItemChanged(PlayerId(e.player.uniqueId), itemIdResolver.itemIdOf(stack))
    }

    @EventHandler
    public fun onJoin(e: PlayerJoinEvent) {
        handleTrigger.onHeldItemChanged(
            PlayerId(e.player.uniqueId),
            itemIdResolver.itemIdOf(e.player.inventory.itemInMainHand),
        )
    }

    @EventHandler(ignoreCancelled = true)
    public fun onSwap(e: PlayerSwapHandItemsEvent) {
        // The event carries the stack that moves into the main hand.
        handleTrigger.onHeldItemChanged(PlayerId(e.player.uniqueId), itemIdResolver.itemIdOf(e.mainHandItem))
    }
}
