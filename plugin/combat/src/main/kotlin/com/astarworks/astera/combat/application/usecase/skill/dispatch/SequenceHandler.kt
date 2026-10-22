package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * SEQUENCE archetype handler: a pure mechanics container. No hit check, no status, no lingering
 * state - it dispatches the `cast` phase and nothing else. Meant for skills that are entirely
 * timelines (delayed beats, vfx, damage by targeter) so authors need no dummy melee / buff.
 */
public class SequenceHandler(
    private val dispatcher: MechanicDispatcher,
) {
    @Suppress("UNUSED_PARAMETER")
    public fun handle(
        event: SkillCast,
        params: SkillParams.SequenceParams,
        phases: PhaseMechanics.Sequence,
    ) {
        dispatcher.dispatch(phases.cast, event.toMechanicContext())
    }
}
