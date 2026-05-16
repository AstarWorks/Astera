package com.astarworks.astera.domain.rule

import com.astarworks.astera.domain.model.damage.DamageAttempt
import com.astarworks.astera.domain.model.damage.ResolvedDamage

/**
 * Pure resolution of a [DamageAttempt] into a [ResolvedDamage].
 *
 * Phase 2 mid scope: applies the base profile and (optionally) a headshot
 * multiplier. Element-matrix multipliers are deferred to the Phase 2 mid
 * second batch, where this function will accept an `IElementMatrix` parameter.
 *
 * Keep stateless so the rule remains trivially testable without any Paper
 * harness, mirroring [WeaponDamageRule].
 */
public object DamageRule {

    /**
     * Resolve a [DamageAttempt].
     *
     * `wasCritical` is `true` when the headshot flag actually amplified the
     * damage (i.e. headshot && headshotMultiplier > 1 && base > 0). Shield
     * mechanics are Phase 3 so `wasBlocked` is always `false` here.
     *
     * TODO(Phase 2 mid - second batch): accept an `IElementMatrix` parameter
     * and apply attribute-vs-target matchup multipliers before returning.
     */
    public fun resolve(
        attempt: DamageAttempt,
        headshotMultiplier: Double = 2.0,
    ): ResolvedDamage {
        val base = attempt.baseProfile.base
        val finalAmount = if (attempt.headshot) base * headshotMultiplier else base
        val wasCritical = attempt.headshot && finalAmount > base
        return ResolvedDamage(
            finalAmount = finalAmount,
            attribute = attempt.baseProfile.attribute,
            wasBlocked = false,
            wasCritical = wasCritical,
        )
    }
}
