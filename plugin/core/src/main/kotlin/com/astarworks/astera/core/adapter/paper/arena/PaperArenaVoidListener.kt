package com.astarworks.astera.core.adapter.paper.arena

import com.astarworks.astera.core.application.service.arena.VoidFallKiller
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID

/**
 * Void-fall kill plane of the sky-island arenas (ADR-0246). A survival / adventure player who drops below the
 * world's `kill_y` dies through [VoidFallKiller] (the Astera health container, like every other death), instead of
 * falling for seconds towards vanilla's void damage at the world floor. The player is first put back on the last
 * solid ground they stood on, so the death burst (stars) lands on the island edge they fell from, not in the void.
 */
internal class PaperArenaVoidListener(
    private val killY: (worldName: String) -> Int?,
    private val killer: VoidFallKiller,
) : Listener {

    private val lastGround = HashMap<UUID, Location>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) = check(event.player, event.to)

    /** Teleports (`/tp`, plugin moves) fire their own event type, which `PlayerMoveEvent` handlers do not see. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) = check(event.player, event.to)

    private fun check(player: Player, to: Location) {
        val limit = killY(to.world.name) ?: return
        if (player.gameMode == GameMode.SPECTATOR || player.gameMode == GameMode.CREATIVE || player.isDead) return
        if (to.y >= limit) {
            if (player.isOnGround) lastGround[player.uniqueId] = to.clone()
            return
        }
        val ground = lastGround[player.uniqueId]
        // The death is read at the victim's position (star burst): bring the body back to the edge it fell from first.
        killer.kill(PlayerId(player.uniqueId)) {
            if (ground != null && ground.world == to.world) player.teleport(ground)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        lastGround.remove(event.player.uniqueId)
        killer.forget(PlayerId(event.player.uniqueId))
    }
}
