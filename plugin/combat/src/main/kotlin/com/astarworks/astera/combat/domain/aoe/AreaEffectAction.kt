package com.astarworks.astera.combat.domain.aoe

import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.status.StatusEffectId

/**
 * Per-tick action executed inside an active area effect (Wave 5m / ADR-0088).
 *
 * One of:
 *  - [ApplyStatusEffect] — apply / refresh a status effect on every
 *    entity inside the shape (poison cloud, slow field).
 *  - [DealDamage] — apply a [DamageProfile] to every entity (fire ring,
 *    acid pool). The gateway invokes the standard `ApplyDamageUseCase`
 *    so armor / element-matrix / crits / FF all still apply.
 *  - [SpawnParticle] — purely visual; safe to schedule on any tick
 *    interval without performance worry for the damage pipeline.
 *
 * Phase 6 may add `RunMechanic` (CEL-driven custom action) per ADR-0077;
 * the sealed type lives here so callers can `when` exhaustively.
 */
public sealed interface AreaEffectAction {

    public data class ApplyStatusEffect(public val effectId: StatusEffectId) : AreaEffectAction

    public data class DealDamage(public val profile: DamageProfile) : AreaEffectAction

    public data class SpawnParticle(public val particle: ParticleSpec) : AreaEffectAction
}
