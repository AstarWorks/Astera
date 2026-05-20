package com.astarworks.astera.combat.application.usecase.skill.dispatch

import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.MechanicContext
import com.astarworks.astera.combat.application.service.MechanicDispatcher
import com.astarworks.astera.combat.domain.event.SkillCast
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.combat.domain.skill.params.SkillParams
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics

/**
 * PROJECTILE archetype handler — Wave _skills-2 / ADR-0077 rewrite.
 *
 * Before: synthesized a single [ProjectileSpec] with an `OnHitEffect.DealDamage`
 * baked from `params.damage`, dispatched to `IProjectileService`.
 *
 * After: reads [PhaseMechanics.Projectile] off the spec and routes each phase
 * through [MechanicDispatcher]:
 *
 *  - `cast`    — fires immediately (muzzle flash / cast sound / per-tick trails
 *                with `period_ticks > 0`).
 *  - `hit_entity` / `hit_block` / `miss` / `expire` — fired by the projectile
 *    runtime when its lifecycle reaches that phase. Phase 1 ships only the
 *    `cast` invocation because the projectile-runtime feedback ports
 *    ([IProjectileService] callbacks etc.) are Phase 6 work. The
 *    `hit*` / `miss` / `expire` dispatchers are wired to the projectile spec's
 *    own [com.astarworks.astera.combat.domain.projectile.OnHitEffect] list
 *    only when Phase 6 surfaces the entity / block / expire events.
 *
 * Projectile lifetime is still driven by [SkillParams.ProjectileParams] —
 * `launchSpeed`, `maxLifetimeTicks` and the per-spec [DamageProfile][
 *   com.astarworks.astera.core.domain.damage.DamageProfile] survive as the
 * runtime macro shape; the Mechanic list adds the lifecycle-specific
 * declarative effects on top.
 */
public class ProjectileHandler(
    private val projectileService: IProjectileService,
    private val dispatcher: MechanicDispatcher,
) {
    /**
     * Dispatch the projectile cast: fire the `cast` phase mechanics, then
     * spawn the projectile via [IProjectileService].
     *
     * Phase 1: `hitEntity` / `hitBlock` / `miss` / `expire` lists are NOT
     * yet invoked here — they're parsed but await Phase 6 runtime feedback
     * (see class kdoc).
     */
    public fun handle(
        event: SkillCast,
        params: SkillParams.ProjectileParams,
        phases: PhaseMechanics.Projectile,
    ) {
        val ctx = MechanicContext(
            actorId = event.caster,
            origin = event.at,
            castedSkills = setOf(event.skillId),
        )

        // 1. Fire the cast phase (muzzle flash, sound, per-tick trail kickoff).
        dispatcher.dispatch(phases.cast, ctx)

        // 2. Spawn the projectile via the runtime service. Phase 1 stub may
        //    be a no-op when Astera-Weapons isn't loaded.
        val spec = ProjectileSpec(
            motion = MotionProfile.Linear(initialSpeed = params.launchSpeed, drag = 0.0),
            hit = HitProfile.Sphere(radius = DEFAULT_HIT_RADIUS),
            lifetime = LifetimePolicy.Ticks(maxTicks = params.maxLifetimeTicks),
            onHit = emptyList(),
        )
        projectileService.spawn(
            spec = spec,
            origin = event.at,
            direction = DEFAULT_DIRECTION,
            owner = event.caster,
        )

        // 3. Phase 1: hitEntity / hitBlock / miss / expire wait on Phase 6
        //    projectile-runtime feedback. The mechanic lists are intentionally
        //    held — Phase 6 will register listeners that dispatch these.
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
