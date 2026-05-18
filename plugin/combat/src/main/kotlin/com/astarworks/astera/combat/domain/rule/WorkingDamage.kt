package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageResolution
import com.astarworks.astera.combat.domain.damage.MitigationApplied
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Mutable accumulator used inside [DamageRule.resolve]'s modifier pipeline.
 *
 * Each [DamageModifier] receives this, mutates as needed (reducing
 * [currentAmount] / recording [mitigations] / setting [wasCritical] etc.),
 * and returns it. At the end of the pipeline [toResolution] freezes the
 * accumulator into an immutable [DamageResolution].
 *
 * Mutable on purpose — the alternative (allocating a new immutable object per
 * modifier per damage tick) is GC pressure we can't afford on the hot path.
 * Confined to a single function call so escape is impossible.
 */
public class WorkingDamage(
    public var currentAmount: Double,
    public val attribute: DamageAttribute,
    public val mitigations: MutableList<MitigationApplied> = mutableListOf(),
    public var wasCritical: Boolean = false,
    public var criticalMultiplier: Double = 1.0,
    public var knockback: Vec3? = null,
) {
    /** Multiply the running amount; clamps to >= 0. */
    public fun multiplyBy(factor: Double) {
        currentAmount = (currentAmount * factor).coerceAtLeast(0.0)
    }

    /** Reduce by [absoluteAmount]; clamps to >= 0. Returns the actual reduction. */
    public fun reduceBy(absoluteAmount: Double): Double {
        val actual = absoluteAmount.coerceAtMost(currentAmount).coerceAtLeast(0.0)
        currentAmount -= actual
        return actual
    }

    public fun recordMitigation(mit: MitigationApplied) {
        mitigations += mit
    }

    public fun markCritical(multiplier: Double) {
        wasCritical = true
        criticalMultiplier = multiplier
    }

    public fun toResolution(): DamageResolution = DamageResolution(
        resolved = ResolvedDamage(
            finalAmount = currentAmount.coerceAtLeast(0.0),
            attribute = attribute,
            wasBlocked = false,
            wasCritical = wasCritical,
        ),
        mitigations = mitigations.toList(),
        knockback = knockback,
        wasCritical = wasCritical,
        criticalMultiplier = criticalMultiplier,
    )

    public companion object {
        /**
         * Initial state from a [DamageAttempt] + element matrix lookup. The
         * attacker's attribute is applied via [IElementMatrix.multiplier] when
         * a defender attribute is supplied.
         */
        public fun from(
            attempt: DamageAttempt,
            elementMatrix: IElementMatrix,
            defenderAttribute: DamageAttribute?,
        ): WorkingDamage {
            val attackerAttr = attempt.baseProfile.attribute
            val base = attempt.baseProfile.base
            val afterElement = if (defenderAttribute != null) {
                base * elementMatrix.multiplier(attackerAttr, defenderAttribute)
            } else {
                base
            }
            return WorkingDamage(currentAmount = afterElement, attribute = attackerAttr)
        }
    }
}
