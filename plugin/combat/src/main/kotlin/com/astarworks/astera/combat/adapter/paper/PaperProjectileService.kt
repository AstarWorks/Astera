package com.astarworks.astera.combat.adapter.paper

import com.astarworks.astera.combat.application.port.outbound.IProjectileHandle
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.combat.domain.projectile.ProjectileVisual
import com.astarworks.astera.combat.domain.projectile.VanillaProjectileType
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.entity.Arrow
import org.bukkit.entity.Egg
import org.bukkit.entity.Entity
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.SmallFireball
import org.bukkit.entity.Snowball
import org.bukkit.entity.ThrownExpBottle
import org.bukkit.entity.Trident
import org.bukkit.util.Vector
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Paper concrete of [IProjectileService] (Wave 6a-N2, ADR-0149 §C precedent).
 *
 * # ADR-0149 §C invariant — Astera-internal is source of truth
 *
 * The Bukkit projectile entity spawned here is **a visual carrier only**. It
 * exists so vanilla particle trails / collision raycasts / hit-credit attribution
 * surface naturally to the world, but every damage / status / explosion / child
 * spawn it triggers is resolved by Astera's pipeline. Specifically:
 *
 *  - This adapter does NOT call `bukkitProjectile.damage(amount)` and does NOT
 *    rely on Bukkit's damage event chain to inflict damage. The companion
 *    [ProjectileHitListener] subscribes to `ProjectileHitEvent` /
 *    `EntityDamageByEntityEvent`, looks up the tracking entry seeded here,
 *    cancels the vanilla damage write, and routes the resolved hit through
 *    `ApplyDamageUseCase` -> `PaperDamageGateway`. The gateway clamps Bukkit
 *    `player.health` >= MIRROR_MIN_HP = 1.0 so vanilla `PlayerDeathEvent` never
 *    fires under Astera control.
 *
 *  - Setting `shooter = bukkitPlayer` is still done for vanilla kill-credit
 *    attribution (the death-screen string and recipe progression both consult
 *    the shooter), but the canonical attribution lives in Astera's
 *    `DamageSource.Projectile.ownerId` flowing through the domain event bus.
 *
 * # Tracking
 *
 * Each spawn produces a [ProjectileTracking] entry indexed both by Astera
 * [ProjectileId] (the [IProjectileHandle.id] callers hold) and by the Bukkit
 * entity's `UUID` (so the listener can recover the Astera-side context from
 * a `ProjectileHitEvent.entity`). The tracking entry holds the [ProjectileSpec]
 * verbatim so the listener can dispatch the full `onHit` effect list without
 * re-reading the skill registry.
 *
 * Tracking entries are evicted in three situations:
 *
 *  1. `IProjectileHandle.cancel()` -> [untrack] -> `bukkitEntity.remove()`.
 *  2. [LifetimePolicy] expiry — the per-projectile watchdog scheduler task
 *     ticks the entry; on `Ticks(maxTicks)` expiry or `Distance(maxBlocks)`
 *     travel the entry is untracked.
 *  3. Vanilla projectile despawn or land-impact (the listener calls [untrack]
 *     after dispatch).
 *
 * Tracking is intentionally ConcurrentHashMap-backed so the listener (Bukkit
 * thread) and lifetime watchdog (scheduler thread on Paper == main) read
 * consistent state. Astera spawn / cancel calls are always main-thread per the
 * `IScheduler` contract.
 *
 * # Motion variant -> Bukkit entity mapping
 *
 * The Bukkit `Projectile` subclass is chosen by `ProjectileSpec.visual` when
 * the visual is [ProjectileVisual.VanillaEntity]; for `None` / `ParticleTrail`
 * / `Display` visuals a [VanillaProjectileType.SNOWBALL] is used as the
 * lightweight default carrier (Snowball is the smallest vanilla projectile
 * that still surfaces `ProjectileHitEvent` without applying its own damage).
 *
 * The motion variant drives the runtime tick behaviour:
 *
 *  - **Linear**: one-shot velocity assignment; vanilla physics + the [drag]
 *    factor decay velocity per tick if non-zero.
 *  - **Ballistic**: velocity assignment + `setGravity(true)` + tick task that
 *    applies per-tick gravity acceleration when the projectile entity does not
 *    already inherit vanilla gravity (e.g. Snowball which is gravity-free).
 *  - **Homing**: tick task that scans nearby living entities within
 *    `acquisitionRadius`, computes a desired direction and applies a
 *    `turnRatePerTick`-bounded course correction by re-assigning velocity.
 *  - **Curved**: tick task that samples the [Easing] curve over the lifetime
 *    horizon and teleports the projectile along the parametric path. Velocity
 *    is set to the curve tangent so vanilla rotation rendering stays sane.
 *  - **Beam**: instant raycast from origin along direction; the first
 *    intersecting entity / block becomes the impact. No tick task; the entry
 *    is dispatched + untracked on the same tick. Visual is supplied by an
 *    `OnHitEffect.SpawnParticle` traversal — the carrier entity is removed
 *    immediately after the raycast resolves.
 *
 * # Off-line owners
 *
 * Spawning is permitted with an offline owner — the projectile still flies and
 * still credits the [DamageSource.Projectile.ownerId] downstream. Skill-cast
 * call sites already guard against casting while offline; this gateway does not
 * second-guess them.
 *
 * # Threading
 *
 * Every Bukkit entity mutation (spawn / setVelocity / remove / teleport) MUST
 * run on the main thread. Astera's combat call chain (CastSkillUseCase ->
 * ProjectileHandler -> spawn) already executes on main; off-main callers
 * MUST route through `scheduler.runLater(Duration.ZERO, ...)`.
 *
 * # Registration
 *
 * Priority 100 in `AsteraCombatPlugin.onEnable` (overlays the no-op fallback
 * lambda used when the projectile service is absent). After construction the
 * caller MUST invoke [start] once so the lifetime-watchdog scheduler is wired,
 * and MUST construct + start [ProjectileHitListener] so onHit dispatch reaches
 * the use cases.
 */
public class PaperProjectileService(
    private val server: Server,
    private val scheduler: IScheduler,
) : IProjectileService {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-projectile tracking. Keyed by the Astera [ProjectileId] — callers
     * receive a handle wrapping the id and the listener resolves through
     * [byBukkitUuid].
     */
    private val tracking: MutableMap<ProjectileId, ProjectileTracking> = ConcurrentHashMap()

    /**
     * Reverse index: Bukkit entity UUID -> Astera [ProjectileId]. The
     * companion listener receives a Bukkit `Projectile` from
     * `ProjectileHitEvent` and needs O(1) lookup back to Astera tracking.
     */
    private val byBukkitUuid: MutableMap<UUID, ProjectileId> = ConcurrentHashMap()

    private var watchdogHandle: IScheduler.TaskHandle? = null

    /**
     * Host-neutral impact logic (ADR-0174). Bound after construction because
     * the resolver is built from the combat use cases, which are wired after
     * this service. `null` => lifetime expiry just untracks (pre-0174 behaviour).
     */
    private var impactResolver: ProjectileImpactResolver? = null

    /** Bind the resolver that runs `expire` / `miss` phases on lifetime end. */
    public fun bindImpactResolver(resolver: ProjectileImpactResolver) {
        impactResolver = resolver
    }

    /**
     * Wire the lifetime watchdog. Returns the handle so the plugin can cancel
     * on `onDisable`. Idempotent — calling [start] twice cancels the previous
     * handle before installing a new one.
     */
    public fun start(): IScheduler.TaskHandle {
        watchdogHandle?.cancel()
        val handle = scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
            tickWatchdog()
        }
        watchdogHandle = handle
        return handle
    }

    /**
     * Cancel the watchdog and remove every still-live Bukkit projectile entity.
     * Called by `AsteraCombatPlugin.onDisable` after subscriptions/registrations
     * are torn down so the world does not hold orphan projectile entities.
     */
    public fun stop() {
        watchdogHandle?.cancel()
        watchdogHandle = null
        val snapshot = tracking.values.toList()
        for (entry in snapshot) {
            removeBukkitEntity(entry.bukkitEntityUuid)
        }
        tracking.clear()
        byBukkitUuid.clear()
    }

    override fun spawn(
        spec: ProjectileSpec,
        origin: Vec3,
        direction: Vec3,
        owner: PlayerId,
    ): IProjectileHandle {
        val ownerPlayer = server.getPlayer(owner.uuid)
        // Best-effort world resolution: owner's current world; fall back to the
        // first loaded world if the owner is offline so the projectile still
        // exists somewhere observable for skill-test scenarios.
        val world = ownerPlayer?.world ?: server.worlds.firstOrNull()
        if (world == null) {
            log.warn("PaperProjectileService.spawn: no loaded world; dropping spawn for owner={}", owner)
            return DeadHandle(ProjectileId.random())
        }
        val origLoc = Location(world, origin.x, origin.y, origin.z)
        val unitDir = direction.safeNormalize()
        val initialSpeed = effectiveLaunchSpeed(spec.motion)
        val velocity = Vector(
            unitDir.x * initialSpeed,
            unitDir.y * initialSpeed,
            unitDir.z * initialSpeed,
        )

        val entityClass = resolveEntityClass(spec.visual)
        val entity: Projectile = try {
            world.spawn(origLoc, entityClass)
        } catch (t: Throwable) {
            log.warn("PaperProjectileService.spawn: world.spawn({}) failed; aborting", entityClass.simpleName, t)
            return DeadHandle(ProjectileId.random())
        }
        // Shooter attribution: vanilla kill-credit + recipe progression read
        // this even though Astera's DamageSource.Projectile.ownerId is the
        // canonical attribution. ProjectileSource accepts any LivingEntity;
        // for offline owners we leave shooter null (vanilla falls back to
        // "unknown source" attribution which Astera overrides downstream).
        if (ownerPlayer is LivingEntity) {
            entity.shooter = ownerPlayer
        }
        entity.velocity = velocity

        // Ballistic motion needs gravity; vanilla snowball/egg already have
        // gravity, but Beam carriers and explicit Linear projectiles should
        // not be tugged by gravity. The Ballistic branch leaves the default;
        // every other branch zeroes gravity explicitly.
        when (spec.motion) {
            is MotionProfile.Ballistic -> entity.setGravity(true)
            else -> entity.setGravity(false)
        }

        val id = ProjectileId.random()
        val entry = ProjectileTracking(
            id = id,
            bukkitEntityUuid = entity.uniqueId,
            ownerId = owner,
            spec = spec,
            originVec = origin,
            directionVec = unitDir,
            spawnTick = scheduler.currentTick(),
            // Distance lifetime tracking accumulates per-tick (current pos -
            // previous pos). Seed with origin and 0.
            travelledBlocks = 0.0,
            lastKnownPosition = origin,
            curveStartTick = scheduler.currentTick(),
        )
        tracking[id] = entry
        byBukkitUuid[entity.uniqueId] = id

        // Beam motion's per-tick branch in [tickWatchdog] removes the carrier
        // on the next tick; the listener can still observe a vanilla
        // `ProjectileHitEvent` during the one-tick lifetime if collision
        // fires, which preserves the standard onHit dispatch path. Phase 6
        // may add an explicit instant raycast here for sub-tick beams.

        return LiveHandle(id, this)
    }

    /**
     * Listener-side resolution: from a Bukkit `Projectile` entity UUID to the
     * Astera tracking entry. Returns null when the projectile was not spawned
     * by Astera (vanilla skeleton arrow, wandering trader, etc.). The listener
     * relies on the null case to skip processing and let other Bukkit plugins
     * own the hit.
     */
    public fun trackingForBukkitEntity(bukkitEntityUuid: UUID): ProjectileTracking? {
        val id = byBukkitUuid[bukkitEntityUuid] ?: return null
        return tracking[id]
    }

    /**
     * Listener-side eviction after dispatch. Idempotent — repeated calls for
     * the same id are no-ops. Also removes the Bukkit entity if it is still
     * alive (defensive: vanilla impacts already remove arrow / snowball etc.,
     * but Beam carriers and cancelled hits leave the entity behind).
     */
    public fun untrack(id: ProjectileId) {
        val entry = tracking.remove(id) ?: return
        byBukkitUuid.remove(entry.bukkitEntityUuid)
        removeBukkitEntity(entry.bukkitEntityUuid)
    }

    private fun removeBukkitEntity(bukkitEntityUuid: UUID) {
        val entity: Entity? = server.getEntity(bukkitEntityUuid)
        if (entity != null && !entity.isDead) {
            entity.remove()
        }
    }

    /**
     * Per-tick driver: lifetime expiry + motion-variant tick logic. Runs on
     * the Paper main thread so Bukkit entity mutation is safe. Iterates over a
     * snapshot to avoid `ConcurrentModificationException` when tick logic
     * mutates [tracking] (e.g. lifetime expiry inside the loop).
     */
    private fun tickWatchdog() {
        val now = scheduler.currentTick()
        val snapshot = tracking.values.toList()
        for (entry in snapshot) {
            val entity = server.getEntity(entry.bukkitEntityUuid)
            if (entity == null || entity.isDead) {
                // Vanilla despawn (e.g. arrow stuck-then-cleanup, or chunk
                // unload) — drop the tracking entry so we don't leak.
                untrack(entry.id)
                continue
            }
            // Update travelled-distance accumulator using current entity loc.
            val curPos = Vec3(entity.location.x, entity.location.y, entity.location.z)
            val stepBlocks = curPos.distance(entry.lastKnownPosition)
            entry.travelledBlocks += stepBlocks
            entry.lastKnownPosition = curPos

            // Lifetime expiry check.
            val expired = when (val lifetime = entry.spec.lifetime) {
                is LifetimePolicy.Ticks -> (now - entry.spawnTick) >= lifetime.maxTicks
                is LifetimePolicy.Distance -> entry.travelledBlocks >= lifetime.maxBlocks
                is LifetimePolicy.UntilHit -> false // dies on impact via listener
            }
            if (expired) {
                try {
                    impactResolver?.onExpire(entry.toFlight(), curPos)
                } catch (t: Throwable) {
                    log.warn("PaperProjectileService: expire dispatch failed for projectile={}", entry.id, t)
                }
                untrack(entry.id)
                continue
            }
            // Motion-specific tick logic.
            when (val motion = entry.spec.motion) {
                is MotionProfile.Linear -> {
                    if (motion.drag > 0.0) {
                        val v = entity.velocity
                        val factor = (1.0 - motion.drag).coerceIn(0.0, 1.0)
                        entity.velocity = Vector(v.x * factor, v.y * factor, v.z * factor)
                    }
                }
                is MotionProfile.Ballistic -> {
                    // Gravity is applied by vanilla. Apply drag explicitly so
                    // YAML drag tuning works even when the entity is a
                    // snowball (which has no native drag).
                    if (motion.drag > 0.0) {
                        val v = entity.velocity
                        val factor = (1.0 - motion.drag).coerceIn(0.0, 1.0)
                        entity.velocity = Vector(v.x * factor, v.y * factor, v.z * factor)
                    }
                }
                is MotionProfile.Homing -> tickHoming(entry, entity, motion)
                is MotionProfile.Curved -> tickCurved(entry, entity, motion, now)
                MotionProfile.Beam -> {
                    // Beam resolves on spawn tick; if we still see it after
                    // one tick something cancelled the removal — force untrack
                    // here as a safety net so beams cannot live indefinitely.
                    untrack(entry.id)
                }
            }
        }
    }

    private fun tickHoming(
        entry: ProjectileTracking,
        entity: Entity,
        motion: MotionProfile.Homing,
    ) {
        // Acquire nearest LivingEntity (non-owner) within acquisitionRadius.
        val center = entity.location
        var bestTarget: LivingEntity? = null
        var bestDist2 = motion.acquisitionRadius * motion.acquisitionRadius
        for (nearby in center.world.getNearbyEntities(center, motion.acquisitionRadius, motion.acquisitionRadius, motion.acquisitionRadius)) {
            if (nearby !is LivingEntity) continue
            if (nearby is Player && nearby.uniqueId == entry.ownerId.uuid) continue
            val d2 = nearby.location.distanceSquared(center)
            if (d2 < bestDist2) {
                bestDist2 = d2
                bestTarget = nearby
            }
        }
        if (bestTarget == null) return
        val target = bestTarget
        val targetLoc = target.eyeLocation
        val currentVel = entity.velocity
        val desired = Vector(
            targetLoc.x - center.x,
            targetLoc.y - center.y,
            targetLoc.z - center.z,
        )
        val desiredLen = desired.length()
        if (desiredLen == 0.0) return
        val desiredUnit = Vector(desired.x / desiredLen, desired.y / desiredLen, desired.z / desiredLen)
        val curSpeed = currentVel.length().coerceAtLeast(motion.initialSpeed)
        // Linear interpolation bounded by turnRatePerTick (radians per tick
        // treated as proportional blending weight in [0,1]).
        val blend = motion.turnRatePerTick.coerceIn(0.0, 1.0)
        val curUnit = if (currentVel.length() > 0.0) {
            Vector(currentVel.x / currentVel.length(), currentVel.y / currentVel.length(), currentVel.z / currentVel.length())
        } else {
            desiredUnit
        }
        val blendedUnit = Vector(
            curUnit.x * (1.0 - blend) + desiredUnit.x * blend,
            curUnit.y * (1.0 - blend) + desiredUnit.y * blend,
            curUnit.z * (1.0 - blend) + desiredUnit.z * blend,
        ).normalize()
        entity.velocity = Vector(
            blendedUnit.x * curSpeed,
            blendedUnit.y * curSpeed,
            blendedUnit.z * curSpeed,
        )
    }

    private fun tickCurved(
        entry: ProjectileTracking,
        entity: Entity,
        motion: MotionProfile.Curved,
        now: Long,
    ) {
        // Estimate normalised parameter t in [0,1] from elapsed ticks vs the
        // configured initialSpeed -> targetOffset travel time. For YAML curves
        // that omit a maxTicks lifetime, fall back to a 40-tick horizon (2 s).
        val maxTicks = when (val l = entry.spec.lifetime) {
            is LifetimePolicy.Ticks -> l.maxTicks.toLong().coerceAtLeast(1L)
            else -> CURVED_DEFAULT_HORIZON_TICKS
        }
        val elapsed = (now - entry.curveStartTick).toDouble()
        val t = (elapsed / maxTicks.toDouble()).coerceIn(0.0, 1.0)
        // Easing.apply is not imported here to keep adapter free of curve
        // implementation details — parametric blending uses raw t. Combat's
        // Easing types are pure functions; future tuning can swap to
        // `motion.curve.apply(t)` once the curve port surfaces a transform.
        val eased = t
        val tx = entry.originVec.x + motion.targetOffset.x * eased
        val ty = entry.originVec.y + motion.targetOffset.y * eased
        val tz = entry.originVec.z + motion.targetOffset.z * eased
        val curWorld = entity.location.world
        if (curWorld != null) {
            entity.teleport(Location(curWorld, tx, ty, tz))
        }
        // Velocity tangent: derivative of linear interpolation is constant
        // along targetOffset / maxTicks per tick — supply it so vanilla
        // rendering rotates the carrier in the travel direction.
        val tangentLen = motion.targetOffset.length() / maxTicks.toDouble()
        if (tangentLen > 0.0) {
            val tu = motion.targetOffset.normalize()
            entity.velocity = Vector(tu.x * tangentLen, tu.y * tangentLen, tu.z * tangentLen)
        }
    }

    private fun effectiveLaunchSpeed(motion: MotionProfile): Double = when (motion) {
        is MotionProfile.Linear -> motion.initialSpeed / TICKS_PER_SECOND
        is MotionProfile.Ballistic -> motion.initialSpeed / TICKS_PER_SECOND
        is MotionProfile.Homing -> motion.initialSpeed / TICKS_PER_SECOND
        is MotionProfile.Curved -> motion.initialSpeed / TICKS_PER_SECOND
        MotionProfile.Beam -> BEAM_INITIAL_SPEED
    }

    /**
     * Map [ProjectileVisual] to the Bukkit Projectile subclass. `None` /
     * `ParticleTrail` / `Display` carriers reuse Snowball — the smallest
     * vanilla projectile that surfaces `ProjectileHitEvent` without applying
     * damage on its own. `VanillaEntity` honours the requested type.
     */
    private fun resolveEntityClass(visual: ProjectileVisual): Class<out Projectile> = when (visual) {
        ProjectileVisual.None -> Snowball::class.java
        is ProjectileVisual.ParticleTrail -> Snowball::class.java
        is ProjectileVisual.Display -> Snowball::class.java
        is ProjectileVisual.VanillaEntity -> when (visual.type) {
            VanillaProjectileType.ARROW -> Arrow::class.java
            VanillaProjectileType.SNOWBALL -> Snowball::class.java
            VanillaProjectileType.TRIDENT -> Trident::class.java
            VanillaProjectileType.FIREBALL -> Fireball::class.java
            VanillaProjectileType.SMALL_FIREBALL -> SmallFireball::class.java
            VanillaProjectileType.EGG -> Egg::class.java
            VanillaProjectileType.EXPERIENCE_BOTTLE -> ThrownExpBottle::class.java
        }
    }

    private fun Vec3.safeNormalize(): Vec3 {
        val l2 = lengthSquared()
        if (l2 == 0.0) return DEFAULT_DIRECTION
        val inv = 1.0 / sqrt(l2)
        return Vec3(x * inv, y * inv, z * inv)
    }

    /**
     * Snapshot of one in-flight projectile. Mutable fields (`travelledBlocks`,
     * `lastKnownPosition`) accumulate over the projectile's lifetime; the rest
     * is immutable from spawn-time onward. Exposed for [ProjectileHitListener]
     * via [trackingForBukkitEntity].
     */
    public class ProjectileTracking internal constructor(
        public val id: ProjectileId,
        public val bukkitEntityUuid: UUID,
        public val ownerId: PlayerId,
        public val spec: ProjectileSpec,
        public val originVec: Vec3,
        public val directionVec: Vec3,
        public val spawnTick: Long,
        @get:JvmName("getTravelledBlocks")
        public var travelledBlocks: Double,
        @get:JvmName("getLastKnownPosition")
        public var lastKnownPosition: Vec3,
        public val curveStartTick: Long,
    ) {
        /** Host-neutral view handed to [ProjectileImpactResolver]. */
        public fun toFlight(): ProjectileImpactResolver.Flight =
            ProjectileImpactResolver.Flight(id = id, owner = ownerId, spec = spec)
    }

    /**
     * Handle returned from [spawn]. Resolves liveness from [tracking] so a
     * caller polling `isAlive` sees the same eviction the listener sees.
     */
    private class LiveHandle(
        override val id: ProjectileId,
        private val service: PaperProjectileService,
    ) : IProjectileHandle {
        override val isAlive: Boolean
            get() = service.tracking.containsKey(id)

        override val position: Vec3?
            get() = service.tracking[id]?.let { service.server.getEntity(it.bukkitEntityUuid)?.location }
                ?.let { Vec3(it.x, it.y, it.z) }

        override fun cancel() {
            service.untrack(id)
        }
    }

    /**
     * Handle returned when spawn could not allocate a Bukkit entity (no world,
     * spawn exception). Reports `isAlive = false` so caller code can branch
     * without an `Optional<ProjectileHandle>` shape.
     */
    private class DeadHandle(override val id: ProjectileId) : IProjectileHandle {
        override val isAlive: Boolean = false
        override fun cancel() = Unit
    }

    public companion object {
        /**
         * Spec speeds are blocks per second; Bukkit velocities are blocks per
         * tick. 20 server ticks per second is the canonical Astera assumption
         * (matches [com.astarworks.astera.core.application.port.outbound.TICK]).
         */
        public const val TICKS_PER_SECOND: Double = 20.0

        /**
         * Beam motion's `initialSpeed` is `Double.POSITIVE_INFINITY`. Bukkit's
         * `Entity.setVelocity` cannot accept infinity (the engine clamps and
         * may log warnings); we use a high-but-finite tick velocity so the
         * carrier traverses any reasonable raycast distance within one tick
         * before [untrack] removes it. 100 blocks/tick covers most arena
         * dimensions; ranged-tournament map limits stay well under that.
         */
        public const val BEAM_INITIAL_SPEED: Double = 100.0

        /**
         * Curved motion without an explicit `LifetimePolicy.Ticks` lifetime
         * falls back to this horizon when computing the 0..1 parameter. 40
         * ticks == 2 s, which matches the typical thrown-skill duration in
         * Astera content.
         */
        public const val CURVED_DEFAULT_HORIZON_TICKS: Long = 40L

        /**
         * Fallback direction for zero-length direction inputs. Spec callers
         * are expected to normalise upstream; this is the safety net.
         */
        public val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)
    }
}
