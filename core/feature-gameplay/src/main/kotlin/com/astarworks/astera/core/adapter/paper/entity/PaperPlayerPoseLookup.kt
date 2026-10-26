package com.astarworks.astera.core.adapter.paper.entity

import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Server

/**
 * Paper concrete of [IPlayerPoseLookup] (ADR-0174). Reads the live Bukkit
 * `Player` on every call — position, eye location and look direction are
 * never cached. Must be called on the main thread (Bukkit entity reads).
 */
public class PaperPlayerPoseLookup(private val server: Server) : IPlayerPoseLookup {

    override fun poseOf(playerId: PlayerId): PlayerPose? {
        val player = server.getPlayer(playerId.uuid) ?: return null
        val loc = player.location
        val eye = player.eyeLocation
        val dir = eye.direction
        val direction = Vec3(dir.x, dir.y, dir.z).normalize()
            .takeIf { it.lengthSquared() > 0.0 } ?: Vec3(0.0, 0.0, 1.0)
        return PlayerPose(
            world = WorldId(loc.world.name),
            position = Vec3(loc.x, loc.y, loc.z),
            eyePosition = Vec3(eye.x, eye.y, eye.z),
            direction = direction,
        )
    }
}
