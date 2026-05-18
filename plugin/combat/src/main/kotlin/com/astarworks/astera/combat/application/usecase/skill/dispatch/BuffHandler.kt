package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams

/**
 * BUFF archetype handler — applies the configured status effect to the
 * caster for the configured duration via [ApplyStatusUseCase].
 *
 * Wave 5a scope is *self-buff only*. A buff that targets an ally (e.g.
 * heal-other) requires a target-selection layer that does not yet exist —
 * Wave 6 will add `targetSelector: TargetSelector` to [SkillParams.BuffParams]
 * once skill targeting modes are formalized.
 */
public class BuffHandler(
    private val applyStatus: ApplyStatusUseCase,
) {
    public fun handle(event: SkillCast, params: SkillParams.BuffParams) {
        applyStatus.execute(
            targetId = event.caster,
            effectId = params.statusEffectId,
            durationTicks = params.durationTicks,
        )
    }
}
