package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Velocity, teleport and dash invulnerability for the skill engine. Invulnerability is the same `immunity` status the
 * damage pipeline already honours ([MechanicStatuses.IMMUNITY]); it is only granted when the entity had none, so a release
 * never strips somebody else's immunity.
 */
public class PaperSkillMovementGateway(
    private val server: Server,
    private val applyStatus: ApplyStatusUseCase,
    private val statuses: IStatusEffectContainer,
) : ISkillMovementGateway {
    private val granted = HashSet<UUID>()

    private fun living(entity: UUID): LivingEntity? = (server.getEntity(entity) as? LivingEntity)?.takeIf { it.isValid && !it.isDead }

    override fun isAlive(entity: UUID): Boolean = living(entity) != null

    override fun setVelocity(entity: UUID, velocity: Vec3) {
        living(entity)?.velocity = Vector(velocity.x, velocity.y, velocity.z)
    }

    override fun addVelocity(entity: UUID, delta: Vec3) {
        val e = living(entity) ?: return
        e.velocity = e.velocity.add(Vector(delta.x, delta.y, delta.z))
    }

    override fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean {
        val e = living(entity) ?: return false
        val at = Location(e.world, to.x, to.y, to.z, yawDeg?.toFloat() ?: e.location.yaw, e.location.pitch)
        e.teleportAsync(at)
        return true
    }

    override fun grantInvulnerable(entity: UUID, safetyTicks: Int): Boolean {
        val id = PlayerId(entity)
        if (living(entity) == null || statuses.has(id, MechanicStatuses.IMMUNITY_ID)) return false
        applyStatus.applySpec(id, MechanicStatuses.IMMUNITY, safetyTicks.coerceAtLeast(1), id)
        granted += entity
        return true
    }

    override fun releaseInvulnerable(entity: UUID) {
        if (granted.remove(entity)) statuses.remove(PlayerId(entity), MechanicStatuses.IMMUNITY_ID)
    }
}
