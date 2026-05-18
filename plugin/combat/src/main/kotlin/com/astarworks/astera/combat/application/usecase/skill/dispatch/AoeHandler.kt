package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.INearbyTargetQuery
import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.weapons.domain.weapon.DamageProfile

/**
 * AOE archetype handler — like [MeleeHandler] but scaled by
 * [SkillParams.AoeParams.falloff] across the radius.
 *
 * Wave 5a simplification
 * ----------------------
 * [INearbyTargetQuery] returns only `PlayerId`s — it does not surface
 * per-target distances. Until Wave 6 adds a richer query response (or until
 * we accept a `Pair<PlayerId, Vec3>`), every target inside the radius is
 * treated as if it were at `t = AOE_AVERAGE_DISTANCE_RATIO` of the radius —
 * a uniform mid-range falloff approximation. The structure (apply easing on
 * a `t` parameter, scale base damage) is preserved so Wave 6 can swap in the
 * real `distance / radius` ratio per target without touching this file's
 * public shape.
 */
public class AoeHandler(
    private val nearbyQuery: INearbyTargetQuery,
    private val applyDamage: ApplyDamageUseCase,
) {
    public fun handle(event: SkillCast, params: SkillParams.AoeParams) {
        val candidates = nearbyQuery.findInRadius(event.at, params.radius)
        if (candidates.isEmpty()) return

        val source = DamageSource.Skill(
            skillId = event.skillId,
            casterId = event.caster,
            casterPosition = event.at,
        )

        // Wave 5a: uniform falloff at AOE_AVERAGE_DISTANCE_RATIO. See class kdoc.
        val falloffMultiplier = (1.0 - params.falloff.apply(AOE_AVERAGE_DISTANCE_RATIO))
            .coerceAtLeast(0.0)
        val scaledBase = params.damage.base * falloffMultiplier
        val scaledProfile = DamageProfile(
            base = scaledBase,
            attribute = params.damage.attribute,
        )

        for (target in candidates) {
            if (target == event.caster) continue
            applyDamage.execute(
                DamageAttempt(
                    targetId = target,
                    baseProfile = scaledProfile,
                    source = source,
                ),
            )
        }
    }

    public companion object {
        /**
         * Uniform falloff parameter used until per-target distances are
         * surfaced (Wave 6). Picked at `0.5` so the easing curve is sampled
         * at the midpoint of its domain — a reasonable expectation value
         * across a roughly uniform target distribution inside the radius.
         */
        public const val AOE_AVERAGE_DISTANCE_RATIO: Double = 0.5
    }
}
