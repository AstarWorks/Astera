package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * MELEE archetype handler — Wave _skills-2 / ADR-0077 rewrite.
 *
 * Fires the `cast` phase immediately (swing sound, cast particle), then runs
 * the legacy "find nearby players in `params.range`, apply baseProfile" sweep
 * — every melee hit becomes a `hit_entity` phase dispatch so authors can mix
 * status / knockback / particle mechanics with the base damage.
 *
 * Phase 1 keeps the [INearbyTargetQuery] + [ApplyDamageUseCase] path that the
 * old handler used for the baseline-damage sweep. Once Phase 6 surfaces
 * per-entity hit events from the Bukkit adapter, this can collapse to a pure
 * "fire cast phase, fire hit_entity per actual hit" loop.
 */
public class MeleeHandler(
    private val nearbyQuery: INearbyTargetQuery,
    private val applyDamage: ApplyDamageUseCase,
    private val dispatcher: MechanicDispatcher,
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.MeleeParams,
        phases: PhaseMechanics.Melee,
    ) {
        val baseCtx = event.toMechanicContext()

        // 1. Fire the cast phase (swing sound, ground particle, etc.).
        dispatcher.dispatch(phases.cast, baseCtx)

        // 2. Find nearby targets via the spatial query.
        val candidates = nearbyQuery.findInRadius(event.at, params.range)
            .filter { it != event.caster }

        if (candidates.isEmpty()) {
            // 3a. No targets → fire the miss phase.
            dispatcher.dispatch(phases.miss, baseCtx)
            return
        }

        // 3b. Per-target: apply baseline damage AND fire hit_entity mechanics.
        val source = DamageSource.Skill(
            skillId = event.skillId,
            casterId = event.caster,
            casterPosition = event.at,
        )
        for (target in candidates) {
            applyDamage.execute(
                DamageAttempt(
                    targetId = target,
                    baseProfile = params.damage,
                    source = source,
                ),
            )
            dispatcher.dispatch(phases.hitEntity, baseCtx.copy(targetId = target))
        }
    }
}
