package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.damage.DamageComponent
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.skill.value.NumericValue

/**
 * AOE archetype handler — Wave _skills-2 / ADR-0077 rewrite.
 *
 * Fires the `cast` phase, finds nearby players, applies the scaled baseline
 * damage (via the legacy `params.damage` + `params.falloff` sweep), then
 * dispatches `hit_entity` mechanics for each target. Finally fires `expire`
 * once the sweep finishes (Phase 1: immediately; Phase 6 will schedule it
 * after the AOE's lingering duration).
 *
 * The Wave 5a "uniform falloff at AOE_AVERAGE_DISTANCE_RATIO" approximation
 * is preserved — Phase 6 will surface per-target distances and apply
 * `params.falloff` accurately.
 */
public class AoeHandler(
    private val nearbyQuery: INearbyTargetQuery,
    private val applyDamage: ApplyDamageUseCase,
    private val dispatcher: MechanicDispatcher,
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.AoeParams,
        phases: PhaseMechanics.Aoe,
    ) {
        val baseCtx = event.toMechanicContext()

        dispatcher.dispatch(phases.cast, baseCtx)

        val candidates = nearbyQuery.findInRadius(event.at, params.radius, event.pose?.world)
            .filter { it != event.caster }

        if (candidates.isNotEmpty()) {
            val source = DamageSource.Skill(
                skillId = event.skillId,
                casterId = event.caster,
                casterPosition = event.at,
            )
            val falloffMultiplier = (1.0 - params.falloff.apply(AOE_AVERAGE_DISTANCE_RATIO))
                .coerceAtLeast(0.0)
            val scaledProfile = scaleProfile(params.damage, falloffMultiplier)

            dispatcher.forEachWithPerStack(candidates, scaledProfile, baseCtx) { target, profile ->
                applyDamage.execute(
                    DamageAttempt(
                        targetId = target,
                        baseProfile = profile,
                        source = source,
                    ),
                )
                dispatcher.dispatch(phases.hitEntity, baseCtx.copy(targetId = target))
            }
        }

        // Phase 1: AOE has no lingering duration plumbing; fire expire now.
        dispatcher.dispatch(phases.expire, baseCtx)
    }

    private fun scaleProfile(profile: DamageProfile, multiplier: Double): DamageProfile {
        if (multiplier == 1.0) return profile
        return DamageProfile(
            profile.components.map { component ->
                val scaled = when (val b = component.base) {
                    is NumericValue.Literal -> NumericValue.Literal(b.value * multiplier)
                    is NumericValue.Cel -> b // Phase 1 keeps CEL untouched; evaluator handles scaling later.
                }
                component.copy(base = scaled)
            },
        )
    }

    public companion object {
        /**
         * Uniform falloff parameter used until per-target distances are
         * surfaced (Phase 6). Picked at `0.5` so the easing curve is sampled
         * at the midpoint of its domain.
         */
        public const val AOE_AVERAGE_DISTANCE_RATIO: Double = 0.5
    }
}
