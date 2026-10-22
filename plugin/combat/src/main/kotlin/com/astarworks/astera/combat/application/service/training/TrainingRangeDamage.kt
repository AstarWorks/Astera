package com.astarworks.astera.combat.application.service.training

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IDamageProtection
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.NearbyTarget
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * [IEntityDamageGateway] for the lobby training dummies (F3-11): a hit lands on the dummy's own HP
 * model ([TrainingDummy]) instead of the host entity, so a dummy never dies, never drops anything and
 * works the same on every host. Every other entity goes to [base].
 *
 * [onHit] runs after each landed hit (damage number, label).
 */
public class TrainingDummyEntityDamageGateway(
    private val base: IEntityDamageGateway?,
    private val dummies: TrainingDummyRegistry,
    private val now: () -> Long,
    private val onHit: (TrainingDummy, ResolvedDamage, DamageSource) -> Unit,
) : IEntityDamageGateway {

    override fun isEntityTarget(target: PlayerId): Boolean =
        dummies.isDummy(target) || base?.isEntityTarget(target) == true

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val dummy = dummies.forBody(target)
            ?: return base?.apply(target, resolved, source)
                ?: Result.failure(DamageApplicationError.TargetOffline(target))
        if (resolved.finalAmount <= 0.0) return Result.failure(DamageApplicationError.Immune(target))
        val remaining = dummy.hit(resolved.finalAmount, now())
        onHit(dummy, resolved, source)
        return Result.success(DamageOutcome.HpReduced(remaining))
    }

    override fun hitPoints(target: PlayerId): Double? =
        dummies.forBody(target)?.hp ?: base?.hitPoints(target)

    override fun maxHitPoints(target: PlayerId): Double? =
        dummies.forBody(target)?.maxHp ?: base?.maxHitPoints(target)

    override fun heal(target: PlayerId, amount: Double) {
        val dummy = dummies.forBody(target)
        if (dummy != null) dummy.heal(amount) else base?.heal(target, amount)
    }
}

/**
 * Players standing in the training range are immune to other players (F3-11): only dummies take damage
 * there. A "player" is anything the pose lookup knows; dummy bodies are never protected.
 */
public class TrainingRangeProtection(
    private val range: () -> ILobbyTrainingRange?,
    private val dummies: TrainingDummyRegistry,
    private val poses: () -> IPlayerPoseLookup?,
) : IDamageProtection {

    override fun isProtected(target: PlayerId): Boolean {
        if (dummies.isDummy(target)) return false
        val training = range() ?: return false
        val pose = poses()?.poseOf(target) ?: return false
        return training.contains(pose.world, pose.position)
    }
}

/** [INearbyTargetQuery] that never offers protected players, so skills do not select them at all. */
public class ProtectionAwareNearbyTargetQuery(
    private val base: INearbyTargetQuery,
    private val protection: IDamageProtection,
) : INearbyTargetQuery {

    override fun findInRadius(center: Vec3, radius: Double, world: WorldId?): List<PlayerId> =
        base.findInRadius(center, radius, world).filterNot(protection::isProtected)

    override fun findNearby(center: Vec3, radius: Double, world: WorldId?): List<NearbyTarget> =
        base.findNearby(center, radius, world).filterNot { protection.isProtected(it.id) }
}
