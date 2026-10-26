package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import org.bukkit.GameMode
import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Global match logic consumes immutable snapshots; live reads belong to each player. */
public class PaperMatchPlayerPositions(private val server: Server, private val scheduler: IScheduler) : IMatchPlayerPositions {
    private data class Snapshot(val location: PlayerLocation, val alive: Boolean)
    private val snapshots = ConcurrentHashMap<PlayerId, Snapshot>()

    init {
        scheduler.repeat(ExecutionScope.Global, ticks(1), ticks(1)) {
            val online = server.onlinePlayers.toList()
            val ids = online.map { PlayerId(it.uniqueId) }.toSet()
            snapshots.keys.retainAll(ids)
            for (player in online) scheduler.run(ExecutionScope.Entity(player.uniqueId), Duration.ZERO) {
                if (player.isOnline) snapshots[PlayerId(player.uniqueId)] = capture(player)
            }
        }
    }

    private fun capture(player: Player): Snapshot {
        val loc = player.location
        return Snapshot(PlayerLocation(WorldId(loc.world.name), Vec3(loc.x, loc.y, loc.z)),
            !player.isDead && player.gameMode != GameMode.SPECTATOR)
    }

    override fun positionOf(playerId: PlayerId, includeDead: Boolean): PlayerLocation? {
        if (scheduler.isOnScope(ExecutionScope.Entity(playerId.uuid))) {
            val player = server.getPlayer(playerId.uuid) ?: return null
            snapshots[playerId] = capture(player)
        }
        val snapshot = snapshots[playerId] ?: return null
        return snapshot.location.takeIf { includeDead || snapshot.alive }
    }
}
