package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.matchmaking.IArrivalSpawns
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Game-server arrival placement (lobby to game hand-off).
 *
 * - The player's login location is set to their team's spawn on the assigned stage, so they appear on the
 *   arena directly instead of at their saved position on the server's flat main world, and no later teleport
 *   races the client's first movement packets.
 * - While the player waits for the others (assigned, match not started) they take no damage.
 *
 * A login for anyone without an assignment is left alone; the receiver bounces strays to the lobby.
 */
public class PaperArrivalSpawnListener(
    private val arrivals: IArrivalSpawns,
) : Listener {

    public fun start(plugin: JavaPlugin): PaperArrivalSpawnListener {
        plugin.server.pluginManager.registerEvents(this, plugin)
        return this
    }

    @EventHandler(priority = EventPriority.HIGH)
    public fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        val uuid = event.connection.profile.id ?: return
        val spawn = arrivals.spawnFor(PlayerId(uuid)) ?: return
        val world = org.bukkit.Bukkit.getWorld(spawn.world.value) ?: return
        event.spawnLocation = Location(world, spawn.pos.x, spawn.pos.y, spawn.pos.z, spawn.yaw, spawn.pitch)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onDamage(event: EntityDamageEvent) {
        val player = event.entity as? Player ?: return
        if (arrivals.isWaiting(PlayerId(player.uniqueId))) event.isCancelled = true
    }
}
