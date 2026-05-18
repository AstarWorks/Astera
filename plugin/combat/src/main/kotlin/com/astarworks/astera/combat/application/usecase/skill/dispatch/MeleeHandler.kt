package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams

/**
 * MELEE archetype handler — finds nearby players within `params.range` of the
 * cast position, excludes the caster, and runs [ApplyDamageUseCase] for each
 * target with the skill's [DamageProfile][com.astarworks.astera.weapons.domain.weapon.DamageProfile].
 *
 * Wave 5a notes
 * -------------
 * - The caster is excluded explicitly here even though [INearbyTargetQuery]
 *   does not promise exclusion; the Wave 6 `PaperNearbyTargetQuery` should
 *   keep that contract (caller filters, not the port).
 * - [DamageSource.Skill.casterPosition] is filled from `event.at` so
 *   `KnockbackModifier` can compute an impulse direction even before Wave 6
 *   adds defender position.
 * - Knockback magnitude from `params.knockback` is not currently piped into
 *   the rule pipeline — the existing `KnockbackModifier` uses its own
 *   `baseMagnitude`. Wave 6 will surface `MeleeParams.knockback` into the
 *   rule via a per-skill modifier override.
 */
public class MeleeHandler(
    private val nearbyQuery: INearbyTargetQuery,
    private val applyDamage: ApplyDamageUseCase,
) {
    public fun handle(event: SkillCast, params: SkillParams.MeleeParams) {
        val candidates = nearbyQuery.findInRadius(event.at, params.range)
        if (candidates.isEmpty()) return

        val source = DamageSource.Skill(
            skillId = event.skillId,
            casterId = event.caster,
            casterPosition = event.at,
        )

        for (target in candidates) {
            if (target == event.caster) continue
            applyDamage.execute(
                DamageAttempt(
                    targetId = target,
                    baseProfile = params.damage,
                    source = source,
                ),
            )
        }
    }
}
