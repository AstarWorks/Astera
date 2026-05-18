package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.weapons.application.port.outbound.IProjectileService
import com.astarworks.astera.weapons.domain.projectile.HitProfile
import com.astarworks.astera.weapons.domain.projectile.LifetimePolicy
import com.astarworks.astera.weapons.domain.projectile.MotionProfile
import com.astarworks.astera.weapons.domain.projectile.OnHitEffect
import com.astarworks.astera.weapons.domain.projectile.ProjectileSpec

/**
 * PROJECTILE archetype handler — synthesizes a [ProjectileSpec] from the
 * skill's [SkillParams.ProjectileParams] and delegates spawning to
 * [IProjectileService].
 *
 * Wave 5a placeholders
 * --------------------
 * - **Direction** defaults to [DEFAULT_DIRECTION] (`+X`) because [SkillCast]
 *   does not yet carry the caster's look vector. Wave 6 will extend the event
 *   (or pass the look vector through `SkillCastDispatcher`) so the projectile
 *   actually points where the caster is aiming.
 * - **Hit radius** is fixed at [DEFAULT_HIT_RADIUS]. The skill YAML doesn't
 *   yet expose per-skill hitbox tuning; once it does, the value moves into
 *   `ProjectileParams`.
 * - **Drag** is 0 (linear motion). When `ProjectileParams` adds a drag field
 *   the spec wiring picks it up automatically.
 */
public class ProjectileHandler(
    private val projectileService: IProjectileService,
) {
    /**
     * Synthesize the [ProjectileSpec] and call [IProjectileService.spawn].
     *
     * Spec composition:
     * - motion: [MotionProfile.Linear] with `initialSpeed = params.launchSpeed`
     * - hit: [HitProfile.Sphere] with [DEFAULT_HIT_RADIUS]
     * - lifetime: [LifetimePolicy.Ticks] with `params.maxLifetimeTicks`
     * - onHit: single [OnHitEffect.DealDamage] carrying `params.damage`
     *
     * Note: `launchSpeed = 0.0` is rejected by [MotionProfile.Linear]
     * (requires `> 0`) — caller-side YAML validation must ensure speed > 0.
     */
    public fun handle(event: SkillCast, params: SkillParams.ProjectileParams) {
        val spec = ProjectileSpec(
            motion = MotionProfile.Linear(
                initialSpeed = params.launchSpeed,
                drag = 0.0,
            ),
            hit = HitProfile.Sphere(radius = DEFAULT_HIT_RADIUS),
            lifetime = LifetimePolicy.Ticks(maxTicks = params.maxLifetimeTicks),
            onHit = listOf(OnHitEffect.DealDamage(profile = params.damage)),
        )
        projectileService.spawn(
            spec = spec,
            origin = event.at,
            direction = DEFAULT_DIRECTION,
            owner = event.caster,
        )
    }

    public companion object {
        /** Default hit-test radius until [SkillParams.ProjectileParams] surfaces one. */
        public const val DEFAULT_HIT_RADIUS: Double = 0.5

        /**
         * Wave 5a placeholder direction: `+X` unit vector. Wave 6 replaces
         * with the caster's look vector once [SkillCast] carries it.
         */
        public val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)
    }
}
