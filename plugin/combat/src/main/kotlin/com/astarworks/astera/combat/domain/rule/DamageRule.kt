package com.astarworks.astera.combat.domain.rule

import com.astarworks.astera.combat.application.port.outbound.IDamageRandomizer
import com.astarworks.astera.combat.domain.armor.ArmorBundle
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageResolution
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.match.TeamId

/**
 * Pipeline resolution of a [DamageAttempt] into a [DamageResolution].
 *
 * Wave 5a refactors the previous static `object DamageRule.resolve(...)` into
 * a class with a pluggable list of [DamageModifier]s. Reasoning (ADR-0030):
 *
 *  - Worker territories (armor / crit / knockback) all needed to extend the
 *    rule. A single static function would force every change to land on the
 *    same file; the pipeline turns each concern into its own modifier file.
 *  - Element matrix + friendly-fire are pipeline pre-stages (not modifiers)
 *    because they short-circuit the pipeline rather than mutating damage.
 *
 * Order of operations:
 *  1. Friendly-fire gate (returns [DamageResolution.blocked] when blocked)
 *  2. Element matrix → seed [WorkingDamage]
 *  3. Each [DamageModifier] in registration order (typical order:
 *     HeadshotModifier → ArmorReductionModifier → CriticalStrikeModifier →
 *     KnockbackModifier)
 *  4. Freeze into [DamageResolution]
 */
public class DamageRule(
    private val elementMatrix: IElementMatrix,
    private val modifiers: List<DamageModifier>,
    private val friendlyFire: FriendlyFirePolicy,
    private val randomizer: IDamageRandomizer,
) {
    public fun resolve(
        attempt: DamageAttempt,
        defenderAttribute: DamageAttribute? = null,
        defenderArmor: ArmorBundle? = null,
        attackerTeam: TeamId? = null,
        defenderTeam: TeamId? = null,
    ): DamageResolution {
        // 1. friendly-fire gate
        if (friendlyFire.shouldBlock(attackerTeam, defenderTeam)) {
            return DamageResolution.blocked(attempt)
        }

        // 2. base × element → WorkingDamage
        var working = WorkingDamage.from(attempt, elementMatrix, defenderAttribute)

        // 3. modifier pipeline
        for (mod in modifiers) {
            working = mod.apply(working, attempt, defenderArmor, randomizer)
        }

        // 4. freeze
        return working.toResolution()
    }
}
