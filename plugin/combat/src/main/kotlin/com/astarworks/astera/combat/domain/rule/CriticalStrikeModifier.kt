package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.core.domain.attribute.IAttributeContainer

/**
 * Random-roll critical strike. On a successful roll the running damage is
 * multiplied by [critMultiplier] and [WorkingDamage.markCritical] is invoked
 * so `ApplyDamageUseCase` can publish a `CriticalHit` event.
 *
 * Composition with [HeadshotModifier]
 * -----------------------------------
 * Crit and headshot stack multiplicatively — both call
 * [WorkingDamage.multiplyBy], so a headshot (2x) + crit (2x) yields 4x base.
 *
 * [WorkingDamage.markCritical] is overwrite-semantics: the *last* multiplier
 * wins for the `criticalMultiplier` field on the resolution. This is
 * acceptable because the field exists as a player-visible signal (kill feed
 * / HUD), not as a damage-derivation field — the actual damage applied is
 * already baked into `currentAmount` via the multiplicative stack.
 *
 * Ordering convention (per [DamageRule] docstring): HeadshotModifier runs
 * *before* CriticalStrikeModifier, so a crit-on-top-of-headshot reports the
 * crit multiplier on the wire (more interesting to gameplay than the
 * headshot tag, which is conveyed separately via `attempt.headshot`).
 */
public class CriticalStrikeModifier(
    public val critChance: Double,
    public val critMultiplier: Double,
) : DamageModifier {
    init {
        require(critChance in 0.0..1.0) {
            "CriticalStrikeModifier.critChance must be in [0,1]: $critChance"
        }
        require(critMultiplier >= 1.0) {
            "CriticalStrikeModifier.critMultiplier must be >= 1.0: $critMultiplier"
        }
    }

    override fun apply(
        working: WorkingDamage,
        attempt: DamageAttempt,
        armor: ArmorBundle?,
        defenderContainer: IAttributeContainer?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage {
        // Fast path: zero chance never rolls.
        if (critChance == 0.0) return working
        val roll = randomizer.nextDouble()
        if (roll < critChance) {
            working.multiplyBy(critMultiplier)
            working.markCritical(critMultiplier)
        }
        return working
    }
}
