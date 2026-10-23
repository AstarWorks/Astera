package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IMatchPlayerPositions
import org.bukkit.GameMode
import org.bukkit.Server

/** Live Bukkit read: online players; alive and non-spectator unless the caller asks to include the dead. */
public class PaperMatchPlayerPositions(private val server: Server) : IMatchPlayerPositions {
    override fun positionOf(playerId: PlayerId, includeDead: Boolean): PlayerLocation? {
        val player = server.getPlayer(playerId.uuid) ?: return null
        if (!includeDead && (player.isDead || player.gameMode == GameMode.SPECTATOR)) return null
        val loc = player.location
        return PlayerLocation(WorldId(loc.world.name), Vec3(loc.x, loc.y, loc.z))
    }
}
