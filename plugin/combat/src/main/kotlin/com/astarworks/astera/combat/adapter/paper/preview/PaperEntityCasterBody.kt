package com.astarworks.astera.combat.adapter.paper.preview

import java.util.concurrent.CompletableFuture

import com.astarworks.astera.combat.application.port.outbound.IEntityCasterBody
import com.astarworks.astera.combat.application.port.outbound.ITickRateControl
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerPose
import com.astarworks.astera.core.domain.world.WorldId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.LivingEntity

/** Paper [IEntityCasterBody]: reads / moves the living entity whose uuid is the caster id. Main thread only. */
internal class PaperEntityCasterBody(private val server: Server, private val plugin: org.bukkit.plugin.Plugin) : IEntityCasterBody {

    override fun poseOf(id: PlayerId): PlayerPose? {
        val entity = living(id) ?: return null
        val loc = entity.location
        val eye = entity.eyeLocation
        val dir = eye.direction
        val direction = Vec3(dir.x, dir.y, dir.z).normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(0.0, 0.0, 1.0)
        return PlayerPose(
            world = WorldId(loc.world.name),
            position = Vec3(loc.x, loc.y, loc.z),
            eyePosition = Vec3(eye.x, eye.y, eye.z),
            direction = direction,
        )
    }

    override fun teleportAsync(id: PlayerId, at: Vec3, world: WorldId?, yaw: Float?, pitch: Float?): CompletableFuture<Boolean> {
        val entity = living(id) ?: return CompletableFuture.completedFuture(false)
        val targetWorld = world?.let { server.getWorld(it.value) } ?: entity.world
        val loc = Location(targetWorld, at.x, at.y, at.z, yaw ?: entity.location.yaw, pitch ?: entity.location.pitch)
        val completed = CompletableFuture<Boolean>()
        entity.teleportAsync(loc).whenComplete { moved, failure ->
            if (!entity.scheduler.execute(plugin, Runnable { completed.complete(failure == null && moved == true) }, { completed.complete(false) }, 1L)) {
                completed.complete(false)
            }
        }
        return completed
    }

    private fun living(id: PlayerId): LivingEntity? = (server.getEntity(id.uuid) as? LivingEntity)?.takeIf { it.isValid }
}

/** Paper [ITickRateControl] over the server tick manager (`/tick`). */
internal class PaperTickRateControl(private val server: Server) : ITickRateControl {
    private val manager get() = server.serverTickManager

    override fun rate(): Float = manager.tickRate

    override fun setRate(ticksPerSecond: Float) {
        manager.tickRate = ticksPerSecond
    }

    override fun isFrozen(): Boolean = manager.isFrozen

    override fun setFrozen(frozen: Boolean) {
        manager.isFrozen = frozen
    }

    override fun step(ticks: Int): Boolean = manager.stepGameIfFrozen(ticks)
}
