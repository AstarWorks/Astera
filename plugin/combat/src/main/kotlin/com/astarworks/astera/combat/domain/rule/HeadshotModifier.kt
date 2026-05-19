package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.core.domain.attribute.IAttributeContainer

/**
 * Headshot multiplier. Preserves the pre-Wave-5a behavior of the legacy
 * `object DamageRule.resolve(...)`: on `attempt.headshot == true` and a
 * multiplier > 1, scale damage and flag `wasCritical`.
 *
 * Element-matrix multipliers do NOT flag `wasCritical` — they're balance, not
 * a player-skill signal. Only headshots and explicit crit rolls do.
 */
public class HeadshotModifier(
    private val multiplier: Double = 2.0,
) : DamageModifier {
    init {
        require(multiplier >= 1.0) { "HeadshotModifier multiplier must be >= 1.0: $multiplier" }
    }

    override fun apply(
        working: WorkingDamage,
        attempt: DamageAttempt,
        armor: ArmorBundle?,
        defenderContainer: IAttributeContainer?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage {
        if (!attempt.headshot) return working
        val before = working.currentAmount
        working.multiplyBy(multiplier)
        // Match legacy semantics: only flag critical when the multiplier
        // actually amplified damage (multiplier > 1 && before > 0).
        if (multiplier > 1.0 && working.currentAmount > before) {
            working.markCritical(multiplier)
        }
        return working
    }
}
