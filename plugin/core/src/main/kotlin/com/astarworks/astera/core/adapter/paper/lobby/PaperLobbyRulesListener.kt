package com.astarworks.astera.core.adapter.paper.lobby

import com.astarworks.astera.core.application.config.LobbyRulesYaml
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyToolBay
import com.astarworks.astera.core.domain.world.WorldId
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
    private val rulesOf: () -> LobbyRulesYaml,
    private val plugin: org.bukkit.plugin.Plugin,
    private val toolBay: () -> ILobbyToolBay? = { null },
    private val onToolBayReset: (Player, Int) -> Unit = { _, _ -> },
    private val spawn: () -> Location?,
) : Listener {

    private fun inLobby(at: Location): Boolean = at.world.name == worldName &&
        kotlin.math.abs(at.x) < 768 && kotlin.math.abs(at.z) < 768

    private fun canBuild(player: Player): Boolean =
        rulesOf().buildEnabled || player.isOp || player.hasPermission(BUILD_PERMISSION)

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
        val block = event.block
        if (!inLobby(block.location)) return
        // The tool test bay (MAP-A6) is the one place hand-mining works: journaled for the restore, no drops.
        val bay = toolBay()
        val id = WorldId(block.world.name)
        if (bay != null && bay.contains(id, block.x, block.y, block.z)) {
            bay.remember(id, block.x, block.y, block.z)
            event.isDropItems = false
            event.expToDrop = 0
            return
        }
        deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlace(event: BlockPlaceEvent) {
        if (inLobby(event.block.location)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (inLobby(event.block.location)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (inLobby(event.block.location)) deny(event, event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val remover = event.remover as? Player ?: return
        if (inLobby(event.entity.location)) deny(event, remover)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        // item frames / armor stands are decoration, not inventory
        if (inLobby(event.rightClicked.location) && event.rightClicked.type.name.let { it == "ITEM_FRAME" || it == "GLOW_ITEM_FRAME" || it == "ARMOR_STAND" }) {
            deny(event, event.player)
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (!inLobby(block.location)) return
        if (event.action == Action.PHYSICAL && block.type == Material.FARMLAND) event.isCancelled = true
        val bay = toolBay() ?: return
        val button = bay.plan.resetButton
        if (event.action == Action.RIGHT_CLICK_BLOCK && event.hand == org.bukkit.inventory.EquipmentSlot.HAND &&
            block.x == button.x && block.y == button.y && block.z == button.z
        ) {
            onToolBayReset(event.player, bay.reset())
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (!inLobby(player.location)) return
        // The lobby is a no-damage zone (pvp, mobs, fire, void...); admin /kill still works.
        val cause = event.cause
        if (cause == EntityDamageEvent.DamageCause.KILL || cause == EntityDamageEvent.DamageCause.SUICIDE) return
        if (cause == EntityDamageEvent.DamageCause.FALL && rulesOf().fallDamage) return
        event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (inLobby(player.location) && !rulesOf().hunger) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val to = event.to
        if (to.blockY >= rulesOf().voidRescueY || !inLobby(to)) return
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
