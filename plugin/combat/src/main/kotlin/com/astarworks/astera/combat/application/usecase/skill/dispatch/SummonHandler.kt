package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SUMMON archetype handler — Wave _skills-2 / ADR-0077 rewrite. The actual
 * entity spawn is still deferred to a Phase 6 `IMobSpawnService` (see the
 * Wave 5a kdoc), so this handler fires the `cast` phase and warn-logs the
 * pending spawn. `spawn` and `summon_death` are parsed but Phase 6 will
 * surface them once the spawn service publishes lifecycle events.
 */
public class SummonHandler(
    private val dispatcher: MechanicDispatcher,
    private val log: Logger = LoggerFactory.getLogger(SummonHandler::class.java),
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.SummonParams,
        phases: PhaseMechanics.Summon,
    ) {
        val ctx = event.toMechanicContext()

        dispatcher.dispatch(phases.cast, ctx)

        log.warn(
            "SUMMON dispatch deferred to Phase 6 mob spawn for skill {} (entityTypeKey={}, count={})",
            event.skillId.value,
            params.entityTypeKey,
            params.count,
        )

        // Phase 1: phases.spawn / phases.summonDeath are not invoked — Phase 6
        // wires them once IMobSpawnService publishes lifecycle events.
    }
}
