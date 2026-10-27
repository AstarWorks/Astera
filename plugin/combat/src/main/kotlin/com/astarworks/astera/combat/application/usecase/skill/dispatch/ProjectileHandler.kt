package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileSkillBinding
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.combat.domain.projectile.ProjectileVisual
import com.astarworks.astera.combat.domain.skill.BlockDamageSpec
import com.astarworks.astera.combat.domain.skill.params.ProjectileVisualParams
import com.astarworks.astera.core.domain.effect.ParticleDefinition
import com.astarworks.astera.core.domain.effect.ParticleId
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * PROJECTILE archetype handler — Wave _skills-2 / ADR-0077, completed by ADR-0174.
 *
 * Fires the `cast` phase through [MechanicDispatcher], then launches the
 * projectile from the caster's eye along their look direction. The skill's
 * `hit_entity` / `hit_block` / `miss` / `expire` phases ride along on the
 * [ProjectileSpec] as a [ProjectileSkillBinding]; the projectile host
 * (Paper listener / simulator) reports impacts to
 * [com.astarworks.astera.combat.application.service.ProjectileImpactResolver],
 * which runs them.
 *
 * Skill params -> projectile:
 *  - `launch_speed` is **blocks per tick** (vanilla convention; a snowball is
 *    ~1.5). [MotionProfile] speeds are blocks/second, hence `* 20`.
 *  - `max_lifetime_ticks` -> [LifetimePolicy.Ticks] (0 falls back to
 *    [DEFAULT_LIFETIME_TICKS]).
 *  - `hit_radius` (optional) -> [HitProfile.Sphere], default [DEFAULT_HIT_RADIUS].
 *  - `muzzle_forward` / `muzzle_up` -> spawn point relative to the eye; `aim_distance` -> aim at the look-ray point
 *    that far away (so a shot spawned above the eye dives onto the target).
 *  - `visual.vfx` -> a `content/vfx` choreography that rides the live projectile and ends with it.
 */
public class ProjectileHandler(
    private val projectileService: IProjectileService,
    private val dispatcher: MechanicDispatcher,
    /** ADR-0196: replays `params.visual.trail` at the projectile each tick; `null` disables trails. */
    private val trailFollower: com.astarworks.astera.combat.application.service.ProjectileTrailFollower? = null,
) {
    /**
     * Fire the `cast` phase, then spawn the projectile via [IProjectileService].
     */
    public fun handle(
        event: SkillCast,
        params: SkillParams.ProjectileParams,
        phases: PhaseMechanics.Projectile,
        blockDamage: BlockDamageSpec? = null,
    ) {
        val ctx = event.toMechanicContext()

        // 1. Cast phase (muzzle flash, sound, per-tick trail kickoff).
        dispatcher.dispatch(phases.cast, ctx)

        // 2. Launch along the caster's look direction from the eye.
        val pose = event.pose
        val look = pose?.direction?.normalize()?.takeIf { it.lengthSquared() > 0.0 } ?: DEFAULT_DIRECTION
        val muzzle = if (pose != null) {
            pose.eyePosition + look * params.muzzleForward + Vec3(0.0, params.muzzleUp, 0.0)
        } else {
            event.at
        }
        // `aim_distance`: dive onto the point the caster looks at instead of flying parallel to the look ray.
        val direction = if (pose != null && params.aimDistance > 0.0) {
            (pose.eyePosition + look * params.aimDistance - muzzle).normalize().takeIf { it.lengthSquared() > 0.0 } ?: look
        } else {
            look
        }

        val spec = ProjectileSpec(
            motion = MotionProfile.Linear(
                initialSpeed = (params.launchSpeed * TICKS_PER_SECOND).coerceAtLeast(MIN_SPEED),
                drag = 0.0,
            ),
            hit = HitProfile.Sphere(radius = params.hitRadius),
            lifetime = LifetimePolicy.Ticks(
                maxTicks = params.maxLifetimeTicks.takeIf { it > 0 } ?: DEFAULT_LIFETIME_TICKS,
            ),
            onHit = emptyList(),
            visual = visualOf(params.visual),
            skill = ProjectileSkillBinding(
                skillId = event.skillId,
                phases = phases,
                castOrigin = event.at,
                worldId = pose?.world,
                pose = pose,
                blockDamage = blockDamage,
            ),
        )
        val handle = projectileService.spawn(
            spec = spec,
            origin = muzzle,
            direction = direction,
            owner = event.caster,
        )

        // 3. Per-tick trail (RTM Effect.Particles.Projectile).
        val trail = params.visual.trail
        val world = pose?.world
        // 3b. A content/vfx choreography riding the live projectile; it ends the tick the projectile does.
        val ridingVfx = params.visual.vfx
        if (ridingVfx != null && world != null) {
            dispatcher.playRidingVfx(
                vfxId = ridingVfx,
                world = world,
                owner = event.caster,
                origin = muzzle,
                direction = direction,
                anchor = { handle.position },
                endWhen = { !handle.isAlive },
            )
        }
        if (trail != null && world != null && trailFollower != null) {
            trailFollower.follow(handle, world, muzzle, direction, trail, params.visual.trailSpacing)
        }
    }

    private fun visualOf(visual: ProjectileVisualParams): ProjectileVisual =
        if (visual.display == null) ProjectileVisual.None else ProjectileVisual(display = visual.display)

    public companion object {
        /** Default hit-test radius when the skill does not set `hit_radius`. */
        public const val DEFAULT_HIT_RADIUS: Double = 0.5

        /** Fallback lifetime for `max_lifetime_ticks: 0`. */
        public const val DEFAULT_LIFETIME_TICKS: Int = 100

        /** Skill speeds are blocks/tick; [MotionProfile] is blocks/second. */
        public const val TICKS_PER_SECOND: Double = 20.0

        /** Floor (blocks/second) so a `launch_speed: 0` skill still builds a valid [MotionProfile]. */
        public const val MIN_SPEED: Double = 0.1

        /** Direction used when the caster pose is unknown (no pose lookup wired). */
        public val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)
    }
}
