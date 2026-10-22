package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: apply a [ResolvedDamage] to a **non-player living entity**
 * (zombie, skeleton, training dummy, ...).
 *
 * The targeting pipeline (targeters, mechanics, [INearbyTargetQuery]) is keyed
 * by [PlayerId]; a non-player entity travels through it as `PlayerId(entityUuid)`.
 * [com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase]
 * asks [isEntityTarget] first and, when true, runs the entity path (damage
 * rule without a player attribute container, this gateway instead of
 * [IDamageGateway], no `PlayerKilled`).
 *
 * Mobs have no Astera HP container: the host's own health model is the source
 * of truth (Paper: `LivingEntity.damage(amount, attacker)`, which also gives
 * vanilla kill credit / loot). ADR-0149's HP-container rule applies to players.
 */
public interface IEntityDamageGateway {
    /** True when [target] is a live, damageable non-player entity in the host. */
    public fun isEntityTarget(target: PlayerId): Boolean

    /** Applies [resolved] to the entity. Failure modes are expected outcomes, not bugs. */
    public fun apply(
        target: PlayerId,
        resolved: ResolvedDamage,
        source: DamageSource,
    ): Result<DamageOutcome, DamageApplicationError>
}
