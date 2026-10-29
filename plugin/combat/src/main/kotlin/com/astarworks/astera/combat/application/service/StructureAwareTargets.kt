package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.damage.attackerIdOrNull
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * WP10: adds world structures (Annihilation pillars, [IStructureTargets]) to
 * the spatial target query. Structures are resolved lazily per call because
 * Match registers them after Combat wires.
 */
public class StructureAwareNearbyTargetQuery(
    private val base: INearbyTargetQuery,
    private val structures: () -> IStructureTargets?,
) : INearbyTargetQuery {

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        base.findInRadius(center, radius, world) + extra(center, radius, world).map { it.id }

    /** Viewers are players only: structures never watch. */
    override fun findViewers(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        base.findViewers(center, radius, world)

    override fun findNearby(center: Vec3, radius: Double, world: WorldId?): List<NearbyTarget> =
        base.findNearby(center, radius, world) +
            extra(center, radius, world).map {
                // NearbyTarget.position is the feet position; aim at the block centre.
                NearbyTarget(
                    it.id,
                    Vec3(it.position.x, it.position.y - STRUCTURE_HEIGHT / 2.0, it.position.z),
                    STRUCTURE_HEIGHT,
                )
            }

    private fun extra(center: Vec3, radius: Double, world: WorldId?): List<IStructureTargets.StructureTarget> =
        structures()?.findInRadius(center, radius, world).orEmpty()

    private companion object {
        /** One block. */
        const val STRUCTURE_HEIGHT: Double = 1.0
    }
}

/**
 * WP10: routes damage addressed to a structure id into [IStructureTargets];
 * every other target goes to [base] (the host's mob gateway, possibly absent).
 */
public class StructureAwareEntityDamageGateway(
    private val base: IEntityDamageGateway?,
    private val structures: () -> IStructureTargets?,
) : IEntityDamageGateway {

    override fun isEntityTarget(target: PlayerId): Boolean =
        structure(target) != null || base?.isEntityTarget(target) == true

    override fun isTrainingTarget(target: PlayerId): Boolean = base?.isTrainingTarget(target) == true

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val structures = structure(target)
            ?: return base?.apply(target, resolved, source)
                ?: Result.failure(DamageApplicationError.TargetOffline(target))
        val amount = resolved.finalAmount
        if (amount <= 0.0) return Result.failure(DamageApplicationError.Immune(target))
        return when (val r = structures.damage(target, source.attackerIdOrNull(), amount, structureSource(source))) {
            is Result.Success ->
                Result.success(if (r.value <= 0.0) DamageOutcome.Killed else DamageOutcome.HpReduced(r.value))
            is Result.Failure -> when (r.error) {
                IStructureTargets.Rejection.UNKNOWN_TARGET ->
                    Result.failure(DamageApplicationError.TargetOffline(target))
                else -> Result.failure(DamageApplicationError.StructureRejected(target, r.error))
            }
        }
    }

    /** Every damage that reaches a structure through the target pipeline is weapon damage; the structure weighs it. */
    private fun structureSource(source: DamageSource): IStructureTargets.DamageSource {
        val origin = when (source) {
            is DamageSource.Item -> source.attackerPosition
            is DamageSource.Skill -> source.casterPosition
            else -> null
        }
        return IStructureTargets.DamageSource(IStructureTargets.DamageKind.WEAPON, origin = origin)
    }

    override fun hitPoints(target: PlayerId): Double? =
        structure(target)?.hitPoints(target) ?: base?.hitPoints(target)

    override fun maxHitPoints(target: PlayerId): Double? =
        structure(target)?.maxHitPoints(target) ?: base?.maxHitPoints(target)

    /** Structures (pillars) are not healable; everything else goes to [base]. */
    override fun heal(target: PlayerId, amount: Double) {
        if (structure(target) == null) base?.heal(target, amount)
    }

    private fun structure(id: PlayerId): IStructureTargets? = structures()?.takeIf { it.isStructure(id) }
}
