package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.core.application.skillengine.runtime.port.BlockHit
import com.astarworks.astera.core.application.skillengine.runtime.port.CastRef
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Paper [ISkillMovementGateway] (R40): caster motion for the dash driver. A teleport keeps the entity's facing unless a yaw /
 * pitch is given. `invulnerable` is the vanilla flag with an expiry the platform ticks ([tick]), so a cast that dies mid-dash
 * cannot leave a caster immortal; [releaseAll] clears every flag on shutdown. Main thread only.
 */
public class PaperSkillMovementGateway(
    private val server: Server,
    private val entityVelocity: PaperEntityVelocity,
) : ISkillMovementGateway {

    private val invulnerableUntil = ConcurrentHashMap<UUID, Int>()
    private var now = 0

    private fun living(entity: UUID): LivingEntity? = server.getEntity(entity) as? LivingEntity

    override fun teleport(cast: CastRef, entity: UUID, pos: Vec3, yaw: Double?, pitch: Double?) {
        val e = living(entity) ?: return EntityGone.skipped("movement.teleport", entity)
        val at = e.location
        e.teleportAsync(Location(e.world, pos.x, pos.y, pos.z, yaw?.toFloat() ?: at.yaw, pitch?.toFloat() ?: at.pitch))
    }

    override fun velocity(cast: CastRef, entity: UUID): Vec3 =
        entityVelocity.of(living(entity) ?: error("skill engine: velocity of entity $entity: not a loaded living entity"))

    override fun addVelocity(cast: CastRef, entity: UUID, v: Vec3) {
        val e = living(entity) ?: return EntityGone.skipped("movement.addVelocity", entity)
        e.velocity = e.velocity.add(Vector(v.x, v.y, v.z))
    }

    override fun probeBlock(cast: CastRef, from: Vec3, to: Vec3): BlockHit? {
        val world = server.getWorld(cast.world.value) ?: error("skill engine: world '${cast.world.value}' of the cast is not loaded")
        val delta = to - from
        val dist = delta.length()
        if (dist < 1.0e-9) return null
        val dir = delta * (1.0 / dist)
        val hit = world.rayTraceBlocks(Location(world, from.x, from.y, from.z), Vector(dir.x, dir.y, dir.z), dist, FluidCollisionMode.NEVER, true) ?: return null
        val block = hit.hitBlock ?: return null
        val face = hit.hitBlockFace?.direction ?: Vector(0.0, 0.0, 0.0)
        return BlockHit(Vec3(hit.hitPosition.x, hit.hitPosition.y, hit.hitPosition.z), Vec3(face.x, face.y, face.z), block.type.key.toString())
    }

    override fun invulnerable(cast: CastRef, entity: UUID, ticks: Int) {
        val e = living(entity) ?: return EntityGone.skipped("movement.invulnerable", entity)
        if (ticks <= 0) {
            invulnerableUntil.remove(entity)
            e.isInvulnerable = false
        } else {
            invulnerableUntil[entity] = now + ticks
            e.isInvulnerable = true
        }
    }

    override fun faceMovement(cast: CastRef, entity: UUID, dir: Vec3) {
        val e = living(entity) ?: return EntityGone.skipped("movement.faceMovement", entity)
        val horizontal = sqrt(dir.x * dir.x + dir.z * dir.z)
        val yaw = Math.toDegrees(atan2(-dir.x, dir.z))
        val pitch = -Math.toDegrees(atan2(dir.y, horizontal))
        e.setRotation(yaw.toFloat(), pitch.toFloat())
    }

    /** Called once per server tick: lifts the expired invulnerability flags. */
    public fun tick(tick: Int) {
        now = tick
        val it = invulnerableUntil.entries.iterator()
        while (it.hasNext()) {
            val (id, until) = it.next()
            if (tick >= until) {
                it.remove()
                living(id)?.isInvulnerable = false
            }
        }
    }

    public fun releaseAll() {
        invulnerableUntil.keys.toList().forEach { living(it)?.isInvulnerable = false }
        invulnerableUntil.clear()
    }
}
