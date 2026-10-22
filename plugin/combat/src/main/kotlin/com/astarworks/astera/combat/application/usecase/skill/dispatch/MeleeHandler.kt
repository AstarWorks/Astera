package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.service.ConeSelector
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.application.port.outbound.IPlayerPoseLookup
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * MELEE archetype handler — Wave _skills-2 / ADR-0077, cone targeting in WP3.
 *
 * Fires the `cast` phase immediately (swing sound, cast particle), then
 * selects targets in a forward cone: apex at the caster's eye, opening along
 * their look direction, `params.range` deep and `params.arcDegrees` wide
 * (full apex angle). Candidates come from [INearbyTargetQuery] and include
 * players and mobs; without a cast pose (no facing known) the cone degrades to
 * the legacy sphere of `range` around the cast origin. Each hit applies the
 * skill damage profile through [ApplyDamageUseCase] (armor / crit / friendly
 * fire; mobs take the entity path) and dispatches `hit_entity`; no target
 * dispatches `miss`.
 */
public class MeleeHandler(
    private val nearbyQuery: INearbyTargetQuery,
    private val applyDamage: ApplyDamageUseCase,
    private val dispatcher: MechanicDispatcher,
    private val poseLookup: IPlayerPoseLookup? = null,
) {
    public fun handle(
        event: SkillCast,
        params: SkillParams.MeleeParams,
        phases: PhaseMechanics.Melee,
    ) {
        val baseCtx = event.toMechanicContext()

        // 1. Fire the cast phase (swing sound, ground particle, etc.).
        dispatcher.dispatch(phases.cast, baseCtx)

        // 2. Select targets: cone when the caster's facing is known, else sphere.
        val targets = selectTargets(event, params)

        if (targets.isEmpty()) {
            // 3a. No targets -> fire the miss phase.
            dispatcher.dispatch(phases.miss, baseCtx)
            return
        }

        // 3b. Per-target: apply baseline damage AND fire hit_entity mechanics.
        val source = DamageSource.Skill(
            skillId = event.skillId,
            casterId = event.caster,
            casterPosition = event.at,
        )
        for (target in targets) {
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

    private fun selectTargets(event: SkillCast, params: SkillParams.MeleeParams): List<PlayerId> {
        val pose = event.pose
        if (pose == null) {
            return nearbyQuery.findInRadius(event.at, params.range)
                .filter { it != event.caster }
        }
        val candidates = nearbyQuery.findNearby(pose.eyePosition, params.range + REACH_MARGIN, pose.world)
            .filter { it.id != event.caster }
            .distinctBy { it.id }
        return ConeSelector.select(
            origin = pose.eyePosition,
            direction = pose.direction,
            range = params.range,
            angleDegrees = params.arcDegrees,
            candidates = candidates,
            fallbackPosition = { poseLookup?.poseOf(it.id)?.position },
        ).map { it.id }
    }

    private companion object {
        /** Query slightly wider than `range`: the cone test measures to the body centre, not the feet. */
        const val REACH_MARGIN: Double = 1.0
    }
}
