package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * BUFF archetype handler — Wave _skills-2 / ADR-0077 rewrite.
 *
 * Apply the legacy [SkillParams.BuffParams] status to the caster (still
 * Phase 1 self-buff only), then run the `cast` phase mechanics. Schedule the
 * `expire` phase to fire after `params.durationTicks` so authors can attach
 * "buff ended" sounds / messages.
 *
 * `removed` (early cancel) is parsed but Phase 1 has no early-cancel plumbing
 * — Phase 6 will surface it.
 */
public class BuffHandler(
    private val applyStatus: ApplyStatusUseCase,
    private val scheduler: IScheduler,
    private val dispatcher: MechanicDispatcher,
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.BuffParams,
        phases: PhaseMechanics.Buff,
    ) {
        val ctx = MechanicContext(
            actorId = event.caster,
            origin = event.at,
            castedSkills = setOf(event.skillId),
        )

        // 1. Apply the legacy status payload.
        applyStatus.execute(
            targetId = event.caster,
            effectId = params.statusEffectId,
            durationTicks = params.durationTicks,
        )

        // 2. Cast phase fires immediately (per-tick mechanics inside cast use
        //    period_ticks so MechanicDispatcher self-schedules them).
        dispatcher.dispatch(phases.cast, ctx)

        // 3. Schedule expire after duration_ticks.
        if (params.durationTicks > 0 && phases.expire.isNotEmpty()) {
            scheduler.runLater(ticks(params.durationTicks.toLong())) {
                dispatcher.dispatch(phases.expire, ctx)
            }
        }
    }
}
