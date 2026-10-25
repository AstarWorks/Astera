package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.bukkit.GameMode
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/**
 * Void-fall kill plane of the sky-island arenas (ADR-0246). A survival / adventure player who drops below the
 * world's `kill_y` dies through the Astera health container (the same path as every other death), instead of
 * falling for seconds towards vanilla's void damage at the world floor.
 */
internal class PaperArenaVoidListener(
    private val killY: (worldName: String, x: Double, z: Double) -> Int?,
    private val health: () -> IPlayerHealthGateway?,
) : Listener {

    private val lastKill = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) = check(event.player, event.to)

    /** Teleports (`/tp`, plugin moves) fire their own event type, which `PlayerMoveEvent` handlers do not see. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = check(event.player, event.to)

    private fun check(player: org.bukkit.entity.Player, to: org.bukkit.Location) {
        val limit = killY(to.world.name, to.x, to.z) ?: return
        if (to.y >= limit) return
        if (player.gameMode == GameMode.SPECTATOR || player.gameMode == GameMode.CREATIVE || player.isDead) return
        // One kill per second: the player keeps falling through the plane while the death flow runs.
        val now = player.world.gameTime
        val previous = lastKill[player.uniqueId]
        if (previous != null && now - previous < KILL_COOLDOWN_TICKS) return
        lastKill[player.uniqueId] = now
        val gateway = health() ?: return
        val id = PlayerId(player.uniqueId)
        if ((gateway.getCurrentHealth(id) ?: return) <= 0.0) return
        gateway.damage(id, VOID_DAMAGE, HealthChangeSource.Other("vanilla-void"))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastKill.remove(event.player.uniqueId)
    }

    private companion object {
        const val VOID_DAMAGE: Double = 1_000_000.0
        const val KILL_COOLDOWN_TICKS: Long = 20L
    }
}
