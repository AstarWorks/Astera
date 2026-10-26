package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.usecase.damage.ApplyDamageUseCase
import com.astarworks.astera.combat.application.usecase.status.ApplyStatusUseCase
import com.astarworks.astera.combat.domain.damage.DamageAttempt
import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ProjectileOrigin
import com.astarworks.astera.combat.domain.projectile.OnHitEffect
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IStructureTargets
import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.skill.mechanic.Mechanic
import com.astarworks.astera.core.domain.skill.phase.PhaseMechanics
import com.astarworks.astera.core.domain.status.StatusEffectId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The three ways a projectile can end; the host calls exactly one of them, once
 * (see [ProjectileImpactResolver], the production implementation).
 */
public interface ProjectileImpactHandler {
    /** The projectile struck an entity at [at] ([target] = the struck player/mob id). */
    public fun onHitEntity(flight: ProjectileImpactResolver.Flight, target: PlayerId?, at: Vec3)

    /** The projectile struck a block at [at]. */
    public fun onHitBlock(flight: ProjectileImpactResolver.Flight, at: Vec3)

    /** The projectile's lifetime ended in flight at [at]. */
    public fun onExpire(flight: ProjectileImpactResolver.Flight, at: Vec3)
}

/**
 * Host-neutral projectile impact resolution (ADR-0174).
 *
 * Extracted from the Paper `ProjectileHitListener` so the production listener
 * and the headless simulator's tick-stepped projectile service drive the exact
 * same logic. The host detects *that* something happened (entity hit, block
 * hit, lifetime end) and calls one of the `on*` methods exactly once per
 * projectile; this class decides *what* it does:
 *
 *  1. the spec's [OnHitEffect] list (damage / status / sound / particle) on
 *     entity or block hits — `Explode` / `SpawnChild` are still held;
 *  2. the skill lifecycle phases from [ProjectileSpec.skill] through
 *     [MechanicDispatcher]:
 *     - entity hit  -> `hit_entity`
 *     - block hit   -> `hit_block`, then `miss`
 *     - lifetime end-> `expire`, then `miss`
 *
 *     (`miss` = the projectile ended without striking an entity.)
 *
 * Phase mechanics run with a [MechanicContext] anchored at the caster's cast
 * origin (`Caster` / `Origin`), `hitLocation` at the impact point and
 * `targetId` at the struck player (hit_entity only).
 *
 * Callers own idempotency (untrack the projectile after the call).
 */
public class ProjectileImpactResolver(
    private val dispatcher: MechanicDispatcher,
    private val applyDamage: ApplyDamageUseCase,
    private val applyStatus: ApplyStatusUseCase,
    private val scheduler: IScheduler,
    private val particleScheduler: IParticleScheduler,
    private val soundScheduler: ISoundScheduler,
    /** WP10: world structures (pillars); a projectile that lands on one hits it like an entity. Lazy: Match registers it later. */
    private val structures: () -> IStructureTargets? = { null },
    private val log: Logger = LoggerFactory.getLogger(ProjectileImpactResolver::class.java),
) : ProjectileImpactHandler {

    /** What the host knows about the projectile at impact time. */
    public data class Flight(
        val id: ProjectileId,
        val owner: PlayerId,
        val spec: ProjectileSpec,
    )

    /**
     * The projectile struck an entity at [at]. [target] is the struck player,
     * or `null` for a non-player entity (phases still run; player-keyed
     * mechanics find no target).
     */
    override fun onHitEntity(flight: Flight, target: PlayerId?, at: Vec3) {
        runOnHitEffects(flight, target, at)
        runPhase(flight, at, target) { it.hitEntity }
    }

    /** The projectile struck a block at [at]. */
    override fun onHitBlock(flight: Flight, at: Vec3) {
        // WP10: landing on (or touching) a damageable structure block is an entity hit on it.
        structureNear(flight, at)?.let { return onHitEntity(flight, it, at) }
        runOnHitEffects(flight, null, at)
        runPhase(flight, at, null) { it.hitBlock }
        runPhase(flight, at, null) { it.miss }
    }

    /** The projectile's lifetime ended in flight at [at] without striking anything. */
    override fun onExpire(flight: Flight, at: Vec3) {
        runPhase(flight, at, null) { it.expire }
        runPhase(flight, at, null) { it.miss }
    }

    private fun structureNear(flight: Flight, at: Vec3): PlayerId? =
        structures()?.findInRadius(at, STRUCTURE_HIT_RADIUS, flight.spec.skill?.worldId)?.minByOrNull { it.position.distanceSquared(at) }?.id

    private fun runPhase(
        flight: Flight,
        at: Vec3,
        target: PlayerId?,
        select: (PhaseMechanics.Projectile) -> List<Mechanic>,
    ) {
        val binding = flight.spec.skill ?: return
        val mechanics = select(binding.phases)
        if (mechanics.isEmpty()) return
        val ctx = MechanicContext(
            maxTargets = flight.spec.skill?.maxTargets,
            actorId = flight.owner,
            targetId = target,
            origin = binding.castOrigin,
            worldId = binding.worldId,
            castedSkills = setOf(binding.skillId),
            pose = binding.pose,
            hitLocation = at,
        )
        dispatcher.dispatch(mechanics, ctx)
    }

    private fun runOnHitEffects(flight: Flight, targetPlayerId: PlayerId?, impactPos: Vec3) {
        val spec = flight.spec
        if (spec.onHit.isEmpty()) return
        val world = spec.skill?.worldId
        val source = DamageSource.Projectile(
            projectileId = flight.id,
            ownerId = flight.owner,
            origin = ProjectileOrigin.FromSkill(spec.skill?.skillId ?: PROJECTILE_FALLBACK_SKILL_ID),
        )
        for (effect in spec.onHit) {
            when (effect) {
                is OnHitEffect.DealDamage -> {
                    // Block hits do not apply player damage (future: DamageSource.Block).
                    if (targetPlayerId == null) continue
                    val result = applyDamage.execute(
                        DamageAttempt(
                            targetId = targetPlayerId,
                            baseProfile = effect.profile,
                            source = source,
                            headshot = false,
                        ),
                    )
                    if (result is Result.Failure && log.isDebugEnabled) {
                        log.debug(
                            "ApplyDamage failed for projectile={} target={} error={}",
                            flight.id, targetPlayerId, result.error,
                        )
                    }
                }
                is OnHitEffect.InflictStatus -> {
                    if (targetPlayerId == null) continue
                    val durationTicks = (effect.duration.inWholeMilliseconds / TICK_MS).toInt()
                        .coerceAtLeast(1)
                    val result = applyStatus.execute(
                        targetPlayerId,
                        StatusEffectId(effect.statusRef.value),
                        durationTicks,
                        flight.owner,
                    )
                    if (result is Result.Failure && log.isDebugEnabled) {
                        log.debug(
                            "ApplyStatus failed for projectile={} target={} ref={} error={}",
                            flight.id, targetPlayerId, effect.statusRef.value, result.error,
                        )
                    }
                }
                is OnHitEffect.Explode ->
                    // Phase 6: routed through IExplosionGateway with falloff.
                    log.debug("Explode effect held for Phase 6 (projectile={}, radius={})", flight.id, effect.radius)
                is OnHitEffect.SpawnChild ->
                    // Phase 6: sub-projectile inheriting parent velocity at impact.
                    log.debug("SpawnChild effect held for Phase 6 (projectile={})", flight.id)
                is OnHitEffect.PlaySound ->
                    soundScheduler.schedule(
                        ScheduledSoundSpec.EmitOnce(
                            sound = effect.sound,
                            position = impactPos,
                            atTick = scheduler.currentTick(),
                            world = world,
                        ),
                    )
                is OnHitEffect.SpawnParticle ->
                    particleScheduler.schedule(
                        ScheduledParticleSpec.EmitOnce(
                            particle = effect.particle,
                            position = impactPos,
                            atTick = scheduler.currentTick(),
                            world = world,
                        ),
                    )
            }
        }
    }

    public companion object {
        /** Ticks are 50 ms (matches `IScheduler`). */
        public const val TICK_MS: Long = 50L

        /** A block hit within this distance of a structure block's centre counts as hitting it. */
        public const val STRUCTURE_HIT_RADIUS: Double = 1.2

        /** Origin skill id for projectiles that carry no [ProjectileSpec.skill] binding. */
        public val PROJECTILE_FALLBACK_SKILL_ID: SkillId = SkillId("projectile")
    }
}
