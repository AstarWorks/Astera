package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.application.config.LobbyRulesYaml
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent

/**
 * Lobby world rules that vanilla gamerules cannot express (ADR-0180):
 * no build / no break for non-ops, no damage, no hunger, void rescue, and
 * "everyone who joins this server starts at the lobby spawn".
 *
 * Runs at LOWEST so it cancels before the BukkitEventAdapter re-routes
 * vanilla damage into the Astera HP container.
 */
internal class PaperLobbyRulesListener(
    private val worldName: String,
    private val rules: LobbyRulesYaml,
    private val plugin: org.bukkit.plugin.Plugin,
    private val spawn: () -> Location?,
) : Listener {

    private fun inLobby(world: World?): Boolean = world != null && world.name == worldName

    private fun canBuild(player: Player): Boolean =
        rules.buildEnabled || player.isOp || player.hasPermission(BUILD_PERMISSION)

    private fun deny(event: Cancellable, player: Player) {
        if (!canBuild(player)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) {
        val target = spawn() ?: return
        val player = event.player
        player.teleportAsync(target).thenAccept { moved ->
            if (moved) player.scheduler.execute(plugin, Runnable { player.foodLevel = 20 }, null, 1L)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBreak(event: BlockBreakEvent) {
        if (inLobby(event.block.world)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlace(event: BlockPlaceEvent) {
        if (inLobby(event.block.world)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (inLobby(event.block.world)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (inLobby(event.block.world)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val remover = event.remover as? Player ?: return
        if (inLobby(event.entity.world)) deny(event, remover)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        // item frames / armor stands are decoration, not inventory
        if (inLobby(event.rightClicked.world) && event.rightClicked.type.name.let { it == "ITEM_FRAME" || it == "GLOW_ITEM_FRAME" || it == "ARMOR_STAND" }) {
            deny(event, event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!inLobby(block.world)) return
        if (event.action == Action.PHYSICAL && block.type == Material.FARMLAND) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!inLobby(player.world)) return
        // The lobby is a no-damage zone (pvp, mobs, fire, void...); admin /kill still works.
        val cause = event.cause
        if (cause == EntityDamageEvent.DamageCause.KILL || cause == EntityDamageEvent.DamageCause.SUICIDE) return
        if (cause == EntityDamageEvent.DamageCause.FALL && rules.fallDamage) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (inLobby(player.world) && !rules.hunger) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (to.blockY >= rules.voidRescueY || !inLobby(to.world)) return
        val target = spawn() ?: return
        val player = event.player
        player.fallDistance = 0f
        player.velocity = player.velocity.zero()
        player.teleportAsync(target)
    }

    companion object {
        const val BUILD_PERMISSION: String = "astera.lobby.build"
    }
}
