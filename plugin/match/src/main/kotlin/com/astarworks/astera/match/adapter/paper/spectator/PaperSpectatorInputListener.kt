package com.astarworks.astera.match.adapter.paper.spectator

import com.astarworks.astera.core.adapter.paper.wiring.OwnedListeners
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.death.SpectatorTargetCycler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin

/**
 * Input handling for players in Bukkit spectator mode (F3-5): dead players in their respawn
 * countdown and eliminated teams.
 *
 *  - Left click = previous target, right click = next target ([SpectatorTargetCycler]).
 *    Both the interact and the swing (animation) event are listened to, because which of them
 *    reaches the server depends on whether the camera is attached; a per-tick guard prevents
 *    one click from cycling twice.
 *  - Spectators cannot interact, damage, drop or pick up anything. Vanilla already blocks most of
 *    it; this also keeps Astera's own item / skill input listeners (which react to plain Bukkit
 *    events) from firing for a spectator.
 */
public class PaperSpectatorInputListener(
    private val cycler: SpectatorTargetCycler?,
) : Listener {

    private val lastCycleTick: MutableMap<UUID, Int> = ConcurrentHashMap()

    public fun start(plugin: JavaPlugin, listeners: OwnedListeners): PaperSpectatorInputListener {
        listeners.register(this)
        return this
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onInteract(event: PlayerInteractEvent) {
        val player = event.player
        if (!player.isSpectator()) return
        event.isCancelled = true
        if (event.hand != EquipmentSlot.HAND) return
        when (event.action) {
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> cycle(player, forward = false)
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> cycle(player, forward = true)
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onSwing(event: PlayerAnimationEvent) {
        if (event.player.isSpectator()) cycle(event.player, forward = false)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (!event.player.isSpectator()) return
        event.isCancelled = true
        if (event.hand == EquipmentSlot.HAND) cycle(event.player, forward = true)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onDamage(event: EntityDamageByEntityEvent) {
        if ((event.damager as? Player)?.isSpectator() == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onPickup(event: EntityPickupItemEvent) {
        if ((event.entity as? Player)?.isSpectator() == true) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public fun onDrop(event: PlayerDropItemEvent) {
        if (event.player.isSpectator()) event.isCancelled = true
    }

    private fun cycle(player: Player, forward: Boolean) {
        val tick = player.ticksLived
        if (lastCycleTick.put(player.uniqueId, tick) == tick) return
        cycler?.cycle(PlayerId(player.uniqueId), forward)
    }

    private fun Player.isSpectator(): Boolean = gameMode == GameMode.SPECTATOR
}
