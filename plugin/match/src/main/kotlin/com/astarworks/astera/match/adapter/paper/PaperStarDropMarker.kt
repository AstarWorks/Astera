package com.astarworks.astera.match.adapter.paper

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.match.application.port.outbound.IStarDropMarker
import org.bukkit.Particle
import org.bukkit.Server

/** A star on the ground is a small glowing puff of END_ROD particles above its position. */
public class PaperStarDropMarker(private val server: Server) : IStarDropMarker {
    override fun mark(world: WorldId, position: Vec3) {
        val w = server.getWorld(world.value) ?: return
        w.spawnParticle(Particle.END_ROD, position.x, position.y + 0.7, position.z, 3, 0.15, 0.25, 0.15, 0.0)
    }
}
