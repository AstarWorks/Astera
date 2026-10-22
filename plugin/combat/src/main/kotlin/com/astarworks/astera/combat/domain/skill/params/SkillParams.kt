package com.astarworks.astera.combat.domain.skill.params

import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.animation.Easing
import com.astarworks.astera.core.domain.damage.DamageProfile

/**
 * Typed parameters for a [com.astarworks.astera.combat.domain.skill.SkillSpec].
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
        /** Full apex angle of the swing cone in degrees (WP3); 360 hits all around. */
        val arcDegrees: Double = DEFAULT_ARC_DEGREES,
    ) : SkillParams() {
        init {
            require(range >= 0.0) { "MeleeParams.range must be >= 0: $range" }
            require(arcDegrees > 0.0 && arcDegrees <= 360.0) {
                "MeleeParams.arcDegrees must be in (0, 360]: $arcDegrees"
            }
        }

        public companion object {
            public const val DEFAULT_ARC_DEGREES: Double = 120.0
        }
    }

    /**
     * Lightweight projectile descriptor. Avoids depending on `ProjectileSpec`
     * (parallel Phase 2 work); only exposes the primitives the skill engine
     * needs to spawn and bound the projectile lifetime. Impact damage is not a
     * param: it is declared by `phases.hit_entity` damage mechanics (WP-G).
     */
    public data class ProjectileParams(
        val launchSpeed: Double,
        val maxLifetimeTicks: Int,
        /** Projectile hit-test sphere radius in blocks (ADR-0174). */
        val hitRadius: Double = DEFAULT_HIT_RADIUS,
        /** What the projectile looks like in flight (particle trail ids + optional display entity). */
        val visual: ProjectileVisualParams = ProjectileVisualParams.NONE,
    ) : SkillParams() {
        init {
            require(hitRadius > 0.0) { "ProjectileParams.hitRadius must be > 0: $hitRadius" }
            require(launchSpeed >= 0.0) { "ProjectileParams.launchSpeed must be >= 0: $launchSpeed" }
            require(maxLifetimeTicks >= 0) {
                "ProjectileParams.maxLifetimeTicks must be >= 0: $maxLifetimeTicks"
            }
        }

        public companion object {
            public const val DEFAULT_HIT_RADIUS: Double = 0.5
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

    /** `sequence` archetype: no parameters, the skill is its `cast` phase mechanics. */
    public data object SequenceParams : SkillParams()

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

/**
 * In-flight look of a skill projectile, as authored in YAML (`params.visual`, ADR-0190 + ADR-0196).
 * [trail] is an RTM-style particle animation replayed at the projectile every tick;
 * [trailSpacing] is the distance in blocks between plays along the segment the projectile
 * covered that tick (RTM `Repeat` sub-steps; keeps a fast projectile's trail a continuous
 * beam; `0` = one play per tick at the new position). [display] is an optional
 * item/block display entity that rides the projectile. Empty = invisible.
 */
public data class ProjectileVisualParams(
    val trail: com.astarworks.astera.core.domain.effect.animation.ParticleAnimation? = null,
    val trailSpacing: Double = DEFAULT_TRAIL_SPACING,
    val display: com.astarworks.astera.combat.domain.projectile.DisplaySpec? = null,
) {
    init {
        require(trailSpacing >= 0.0) { "trailSpacing must be >= 0: $trailSpacing" }
    }

    public companion object {
        public val NONE: ProjectileVisualParams = ProjectileVisualParams()

        /** Blocks between trail plays along a tick's segment when `trail_spacing` is omitted. */
        public const val DEFAULT_TRAIL_SPACING: Double = 0.5
    }
}
