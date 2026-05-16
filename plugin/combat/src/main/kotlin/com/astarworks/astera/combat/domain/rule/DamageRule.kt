package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.damage.DamageAttribute

/**
 * Pure resolution of a [DamageAttempt] into a [ResolvedDamage].
 *
 * Order of operations:
 *   1. Start with `attempt.baseProfile.base`.
 *   2. Multiply by [IElementMatrix.multiplier]`(attackerAttr, defenderAttribute)`
 *      when [defenderAttribute] is supplied (otherwise skipped, leaving the
 *      base untouched — useful for environment/PVE targets without an
 *      element).
 *   3. Multiply by [headshotMultiplier] when `attempt.headshot` is `true`.
 *
 * `wasCritical` is `true` only when the headshot multiplier actually amplified
 * the damage (i.e. headshot && pre-headshot amount > 0 && headshotMultiplier > 1).
 * Element-matrix multipliers do NOT set `wasCritical` — they're balance, not
 * a player-skill signal.
 *
 * Shield mechanics are Phase 3, so `wasBlocked` stays `false`.
 *
 * Keep stateless so the rule remains trivially testable without any Paper
 * harness.
 */
public object DamageRule {

    public fun resolve(
        attempt: DamageAttempt,
        defenderAttribute: DamageAttribute? = null,
        elementMatrix: IElementMatrix = IElementMatrix.Neutral,
        headshotMultiplier: Double = 2.0,
    ): ResolvedDamage {
        val base = attempt.baseProfile.base
        val attackerAttr = attempt.baseProfile.attribute

        val afterElement = if (defenderAttribute != null) {
            base * elementMatrix.multiplier(attackerAttr, defenderAttribute)
        } else {
            base
        }

        val finalAmount = if (attempt.headshot) afterElement * headshotMultiplier else afterElement
        val wasCritical = attempt.headshot && finalAmount > afterElement

        return ResolvedDamage(
            finalAmount = finalAmount,
            attribute = attackerAttr,
            wasBlocked = false,
            wasCritical = wasCritical,
        )
    }
}
