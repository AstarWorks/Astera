package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.application.port.outbound.IKnockbackGateway
import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.port.outbound.aoe.IExplosionGateway
import com.astarworks.astera.combat.domain.aoe.BlockDamagePolicy
import com.astarworks.astera.combat.domain.aoe.ExplosionError
import com.astarworks.astera.combat.domain.aoe.ExplosionResult
import com.astarworks.astera.combat.domain.aoe.ExplosionSpec
import com.astarworks.astera.combat.domain.event.aoe.ExplosionTriggered
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory binding for [IExplosionGateway] (Wave 5m, ADR-0088).
 *
 * Resolves candidate targets via [INearbyTargetQuery.findInRadius] —
 * which returns only `PlayerId` (not positions) — so per-target distance
 * falloff lives in the Paper impl (Wave 6a) where world-space positions
 * are cheap. The InMemory gateway applies **uniform** damage
 * ([ExplosionSpec.damageProfile.base]) to every candidate and a
 * uniform-magnitude knockback impulse.
 *
 * Block destruction is **not** simulated here — the InMemory impl always
 * reports `affectedBlocks = 0`, regardless of [BlockDamagePolicy].
 * Wave 6a `PaperExplosionGateway` overrides at priority 100 to delegate
 * vanilla / Astera block destruction to the live world.
 *
 * The optional [radiusCap] is enforced when non-null; a `null` value
 * (default) lets any radius through.
 *
 * The [damageApplier] SPI lets tests inject a recording / no-op variant;
 * the production wiring binds a `ApplyDamageUseCase`-backed adapter so
 * explosion damage flows through the standard modifier pipeline.
 */
public class InMemoryExplosionGateway(
    private val nearbyQuery: INearbyTargetQuery,
    private val knockbackGateway: IKnockbackGateway,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val damageApplier: ExplosionDamageApplier = ExplosionDamageApplier.None,
    private val radiusCap: Double? = null,
) : IExplosionGateway {

    override fun trigger(spec: ExplosionSpec): Result<ExplosionResult, ExplosionError> {
        radiusCap?.let { cap ->
            if (spec.radius > cap) {
                return Result.failure(ExplosionError.RadiusExceedsCap(spec.radius, cap))
            }
        }

        val candidates: List<PlayerId> = nearbyQuery.findInRadius(
            center = spec.center,
            radius = spec.radius,
            world = spec.world,
        )

        val affected = mutableListOf<Pair<PlayerId, Double>>()
        val baseDamage = spec.damageProfile.base
        for (target in candidates) {
            if (baseDamage <= 0.0) break
            val applied = damageApplier.apply(target, baseDamage, spec)
            affected += target to applied

            if (spec.knockbackMultiplier > 0.0) {
                knockbackGateway.applyImpulse(target, RADIAL_UNIT.times(spec.knockbackMultiplier))
            }
        }

        val result = ExplosionResult(
            affectedPlayers = affected.toList(),
            affectedBlocks = 0,
        )

        eventBus.publish(
            ExplosionTriggered(
                world = spec.world,
                center = spec.center,
                radius = spec.radius,
                source = spec.source,
                affectedPlayers = result.affectedPlayers,
                affectedBlocks = result.affectedBlocks,
                atTick = scheduler.currentTick(),
            ),
        )

        return Result.success(result)
    }

    public companion object {
        /**
         * Placeholder radial impulse for the InMemory gateway. Wave 6a
         * `PaperExplosionGateway` computes the real radial vector from
         * `target.location - center`.
         */
        public val RADIAL_UNIT: Vec3 = Vec3(0.0, 1.0, 0.0)
    }
}

/**
 * SPI for actually applying explosion damage to a target. Decoupled so
 * tests can record damage without wiring an `IDamageGateway`. Wave 5m
 * default = [None] (identity — caller treats `affectedPlayers` as
 * "damage that *would* have been applied").
 *
 * Wave 6a's `PaperExplosionGateway` will inject an
 * `ApplyDamageUseCase`-backed adapter so explosion damage flows through
 * the same modifier pipeline (armor, crits, FF) as direct hits.
 */
public fun interface ExplosionDamageApplier {

    public fun apply(
        target: PlayerId,
        baseDamage: Double,
        spec: ExplosionSpec,
    ): Double

    public companion object {
        public val None: ExplosionDamageApplier = ExplosionDamageApplier { _, baseDamage, _ -> baseDamage }
    }
}
