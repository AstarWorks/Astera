package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.DamageApplicationError
import com.astarworks.astera.combat.application.port.outbound.DamageOutcome
import com.astarworks.astera.combat.application.port.outbound.IEntityDamageGateway
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Host-neutral [IEntityDamageGateway] with a tiny HP model: entities are
 * [spawn]ed with hit points and lose them per hit. Used by unit tests and the
 * headless simulator (mobs there are plain registered ids).
 */
public class InMemoryEntityDamageGateway : IEntityDamageGateway {

    private val hp = ConcurrentHashMap<PlayerId, Double>()
    private val maxHp = ConcurrentHashMap<PlayerId, Double>()

    /** Registers (or resets) [entity] with [hitPoints]. */
    public fun spawn(entity: PlayerId, hitPoints: Double) {
        require(hitPoints > 0.0) { "hitPoints must be > 0: $hitPoints" }
        hp[entity] = hitPoints
        maxHp[entity] = hitPoints
    }

    /** Remaining hit points, or `null` if the entity was never spawned or is dead. */
    public fun hitPointsOf(entity: PlayerId): Double? = hp[entity]?.takeIf { it > 0.0 }

    override fun hitPoints(target: PlayerId): Double? = hp[target]

    override fun maxHitPoints(target: PlayerId): Double? = maxHp[target]

    override fun isEntityTarget(target: PlayerId): Boolean = hp.containsKey(target)

    override fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError> {
        val current = hp[target] ?: return Result.failure(DamageApplicationError.TargetOffline(target))
        if (current <= 0.0) return Result.failure(DamageApplicationError.TargetAlreadyDead(target))
        val remaining = (current - resolved.finalAmount).coerceAtLeast(0.0)
        hp[target] = remaining
        return Result.success(
            if (remaining <= 0.0) DamageOutcome.Killed else DamageOutcome.HpReduced(remaining),
        )
    }
}
