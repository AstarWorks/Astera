package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.damage.DamageAttempt

/**
 * One stage of the [DamageRule] pipeline — armor reduction, crit roll,
 * headshot multiplier, knockback computation, etc.
 *
 * Modifiers are applied in the order supplied to [DamageRule]'s constructor.
 * Each receives the [WorkingDamage] accumulator, mutates it, and returns it
 * (returning a different instance is permitted but rarely needed).
 *
 * Modifiers MUST be pure with respect to external state — the only inputs
 * are the arguments. State that varies per-call (RNG, current tick) must
 * come through the [IDamageRandomizer] port or a parameter on
 * [WorkingDamage]'s seed.
 */
public interface DamageModifier {
    public fun apply(
        working: WorkingDamage,
        attempt: DamageAttempt,
        armor: ArmorBundle?,
        randomizer: IDamageRandomizer,
    ): WorkingDamage
}
