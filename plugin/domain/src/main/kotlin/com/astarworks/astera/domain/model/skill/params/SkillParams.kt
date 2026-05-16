package com.astarworks.astera.domain.model.skill.params

import com.astarworks.astera.domain.animation.Easing
import com.astarworks.astera.domain.model.status.StatusEffectId
import com.astarworks.astera.domain.model.weapon.DamageProfile

/**
 * Typed parameters for a [com.astarworks.astera.domain.model.skill.SkillSpec].
 *
 * Modeled as a sealed hierarchy so the application layer can `when`-dispatch on
 * the archetype without reflection (per `principles.md` §12.5). Each variant
 * carries only the fields its archetype needs; YAML-to-spec conversion in the
 * application layer enforces that the variant matches `archetype`.
 */
public sealed class SkillParams {

    public data class MeleeParams(
        val range: Double,
        val knockback: Double,
        val damage: DamageProfile,
    ) : SkillParams() {
        init {
            require(range >= 0.0) { "MeleeParams.range must be >= 0: $range" }
        }
    }

    /**
     * Lightweight projectile descriptor. Avoids depending on `ProjectileSpec`
     * (parallel Phase 2 work); only exposes the primitives the skill engine
     * needs to spawn and bound the projectile lifetime.
     */
    public data class ProjectileParams(
        val launchSpeed: Double,
        val maxLifetimeTicks: Int,
        val damage: DamageProfile,
    ) : SkillParams() {
        init {
            require(launchSpeed >= 0.0) { "ProjectileParams.launchSpeed must be >= 0: $launchSpeed" }
            require(maxLifetimeTicks >= 0) {
                "ProjectileParams.maxLifetimeTicks must be >= 0: $maxLifetimeTicks"
            }
        }
    }

    /**
     * Area-of-effect parameters. `falloff` scales damage by distance-from-center
     * using the easing curve where `t = distance / radius`.
     */
    public data class AoeParams(
        val radius: Double,
        val damage: DamageProfile,
        val falloff: Easing,
    ) : SkillParams() {
        init {
            require(radius >= 0.0) { "AoeParams.radius must be >= 0: $radius" }
        }
    }

    public data class BuffParams(
        val statusEffectId: StatusEffectId,
        val durationTicks: Int,
    ) : SkillParams() {
        init {
            require(durationTicks >= 0) { "BuffParams.durationTicks must be >= 0: $durationTicks" }
        }
    }

    public data class TeleportParams(
        val maxDistance: Double,
        val requiresLineOfSight: Boolean,
    ) : SkillParams() {
        init {
            require(maxDistance >= 0.0) { "TeleportParams.maxDistance must be >= 0: $maxDistance" }
        }
    }

    /**
     * Summon parameters. `entityTypeKey` is a vendor-neutral entity reference
     * (e.g. `ZOMBIE`, `mythicmobs:custom-imp`); the adapter resolves it.
     */
    public data class SummonParams(
        val entityTypeKey: String,
        val count: Int,
        val lifetimeTicks: Int,
    ) : SkillParams() {
        init {
            require(entityTypeKey.isNotBlank()) { "SummonParams.entityTypeKey must not be blank" }
            require(count >= 1) { "SummonParams.count must be >= 1: $count" }
            require(lifetimeTicks >= 0) { "SummonParams.lifetimeTicks must be >= 0: $lifetimeTicks" }
        }
    }
}
