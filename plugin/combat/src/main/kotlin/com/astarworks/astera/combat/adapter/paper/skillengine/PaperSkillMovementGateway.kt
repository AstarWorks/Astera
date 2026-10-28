package com.astarworks.astera.combat.adapter.paper.skillengine

import com.astarworks.astera.combat.application.service.MechanicStatuses
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.application.port.outbound.IStatusEffectContainer
import com.astarworks.astera.core.application.skillengine.runtime.port.ISkillMovementGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
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
    private val locked = HashSet<UUID>()
    private val lockKey: NamespacedKey = NamespacedKey("astera", "skill_move_lock")

    private fun living(entity: UUID): LivingEntity? = (server.getEntity(entity) as? LivingEntity)?.takeIf { it.isValid && !it.isDead }

    /** The entity whether dead or alive (v1 teleport moves dead targets); null once it is gone. */
    private fun present(entity: UUID): LivingEntity? = server.getEntity(entity) as? LivingEntity

    override fun isAlive(entity: UUID): Boolean = living(entity) != null

    override fun isInWorld(entity: UUID, world: com.astarworks.astera.core.domain.world.WorldId): Boolean =
        present(entity)?.world?.name == world.value

    override fun setVelocity(entity: UUID, velocity: Vec3) {
        living(entity)?.velocity = Vector(velocity.x, velocity.y, velocity.z)
    }

    override fun addVelocity(entity: UUID, delta: Vec3) {
        val e = living(entity) ?: return
        e.velocity = e.velocity.add(Vector(delta.x, delta.y, delta.z))
    }

    override fun teleport(entity: UUID, to: Vec3, yawDeg: Double?): Boolean {
        val e = present(entity) ?: return false
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

    /**
     * Movement lock as a transient walk-speed modifier (`factor - 1`, multiplicative) plus a full jump-strength
     * cut when rooted. Transient modifiers are never saved, so a crash or logout cannot leave a player slowed;
     * the engine releases on cast end / cancel / plugin disable.
     */
    override fun lockMovement(entity: UUID, speedFactor: Double) {
        val e = living(entity) ?: return
        clearLock(e)
        locked += entity
        val factor = speedFactor.coerceIn(0.0, 1.0)
        e.getAttribute(Attribute.MOVEMENT_SPEED)?.addTransientModifier(modifier(factor - 1.0))
        if (factor <= 0.0) e.getAttribute(Attribute.JUMP_STRENGTH)?.addTransientModifier(modifier(-1.0))
    }

    /**
     * Looks the entity up without the alive check: a lock must come off a dying or respawning player too (the engine
     * calls this on cast end, death and quit). An offline player has no entity; transient modifiers die with it.
     */
    override fun unlockMovement(entity: UUID) {
        locked -= entity
        clearLock(server.getEntity(entity) as? LivingEntity ?: return)
    }

    /** Entities currently held by [lockMovement]; for diagnostics and the release-on-quit sweep. */
    public fun lockedEntities(): Set<UUID> = locked.toSet()

    private fun modifier(amount: Double) =
        AttributeModifier(lockKey, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1)

    private fun clearLock(e: LivingEntity) {
        e.getAttribute(Attribute.MOVEMENT_SPEED)?.removeModifier(lockKey)
        e.getAttribute(Attribute.JUMP_STRENGTH)?.removeModifier(lockKey)
    }
}
