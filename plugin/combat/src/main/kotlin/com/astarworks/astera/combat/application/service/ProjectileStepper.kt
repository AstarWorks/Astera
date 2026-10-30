package com.astarworks.astera.combat.application.service

import com.astarworks.astera.core.application.service.observability.ExecutionCosts
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One server-simulated projectile in flight. Mutable: [ProjectileStepper.step]
 * advances [position] / [velocity] / [travelled] in place.
 *
 * [velocity] is in **blocks per tick**. (`MotionProfile.initialSpeed` is blocks
 * per second — see [ProjectileStepper.launchVelocity].)
 */
public class ProjectileFlight(
    public val id: ProjectileId,
    public val owner: PlayerId,
    public val spec: ProjectileSpec,
    public var position: Vec3,
    public var velocity: Vec3,
    public val spawnTick: Long,
    public var travelled: Double = 0.0,
) {
    /** Host-neutral view handed to [ProjectileImpactResolver]. */
    public fun toImpactFlight(): ProjectileImpactResolver.Flight =
        ProjectileImpactResolver.Flight(id = id, owner = owner, spec = spec)
}

/** First thing a projectile's per-tick segment touches. */
public sealed interface ProjectileContact {
    public val at: Vec3

    /** A damageable entity (player, or mob keyed by its entity uuid). */
    public data class Entity(public val target: PlayerId, override val at: Vec3) : ProjectileContact

    /** A solid block (or the world floor / void). */
    public data class Block(override val at: Vec3) : ProjectileContact
}

/**
 * The host's view of the world, queried once per projectile per tick. Paper
 * implements it with `World#rayTrace*`; the headless simulator with its flat
 * world + AABB entities. Implementations must exclude the projectile's owner.
 */
public interface ProjectileWorldProbe {
    /** Earliest entity or block contact along the segment [from] -> [to], or `null`. */
    public fun firstContact(flight: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact?

    /** Aim point of the nearest valid homing target within [radius] of the projectile, or `null`. */
    public fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3?
}

/** What one [ProjectileStepper.step] did. */
public sealed interface ProjectileStepOutcome {
    public data object Flying : ProjectileStepOutcome
    public data class HitEntity(public val target: PlayerId, public val at: Vec3) : ProjectileStepOutcome
    public data class HitBlock(public val at: Vec3) : ProjectileStepOutcome
    public data class Expired(public val at: Vec3) : ProjectileStepOutcome

    public val isTerminal: Boolean get() = this !is Flying
}

/**
 * Host-neutral per-tick projectile stepping (ADR-0174 mitigation for "sim and
 * Paper disagree"): velocity integration (homing steer, gravity, drag),
 * segment contact test through a [ProjectileWorldProbe], lifetime expiry, and the **exactly-once** call into [ProjectileImpactResolver].
 *
 * Paper (`PaperProjectileService`) and the game simulator
 * (`SimProjectileService`) both drive their projectiles through this class and
 * differ only in the probe they hand it, so hit timing is identical by
 * construction.
 *
 * Per tick: steer -> move one velocity step -> first contact on the swept
 * segment (entity/block => terminal at the contact point) -> else apply
 * gravity/drag -> lifetime check (expire => terminal at the new position).
 */
public class ProjectileStepper(
    /** Resolved lazily: the resolver is built by the combat wiring after the projectile service. */
    private val resolver: () -> ProjectileImpactHandler?,
) {

    /** Launch velocity (blocks/tick) for [spec] along the unit [direction]. */
    public fun launchVelocity(spec: ProjectileSpec, direction: Vec3): Vec3 {
        val dir = direction.normalize().takeIf { it.lengthSquared() > 0.0 } ?: DEFAULT_DIRECTION
        return dir * speedPerTick(spec.motion)
    }

    /**
     * Advance [flight] one tick ([now] = current scheduler tick). On a terminal
     * outcome [beforeResolve] runs, then the impact resolver is called exactly
     * once; the caller must then drop the flight.
     */
    public fun step(
        flight: ProjectileFlight,
        now: Long,
        probe: ProjectileWorldProbe,
        beforeResolve: (ProjectileStepOutcome) -> Unit = {},
    ): ProjectileStepOutcome = ExecutionCosts.measure("projectile.step", "unbound") {
        stepFlight(flight, now, probe, beforeResolve)
    }

    private fun stepFlight(
        flight: ProjectileFlight,
        now: Long,
        probe: ProjectileWorldProbe,
        beforeResolve: (ProjectileStepOutcome) -> Unit,
    ): ProjectileStepOutcome {
        val age = now - flight.spawnTick
        steer(flight, probe)
        val from = flight.position
        val to = from + flight.velocity
        val outcome: ProjectileStepOutcome = when (val contact = ExecutionCosts.measure("target.projectile_contact", "unbound") { probe.firstContact(flight, from, to) }) {
            is ProjectileContact.Entity -> {
                flight.position = contact.at
                flight.travelled += from.distance(contact.at)
                ProjectileStepOutcome.HitEntity(contact.target, contact.at)
            }
            is ProjectileContact.Block -> {
                flight.position = contact.at
                flight.travelled += from.distance(contact.at)
                ProjectileStepOutcome.HitBlock(contact.at)
            }
            null -> {
                flight.travelled += from.distance(to)
                flight.position = to
                applyForces(flight)
                if (isExpired(flight, age)) ProjectileStepOutcome.Expired(to) else ProjectileStepOutcome.Flying
            }
        }
        if (outcome.isTerminal) {
            beforeResolve(outcome)
            resolve(flight, outcome)
        }
        return outcome
    }

    private fun resolve(flight: ProjectileFlight, outcome: ProjectileStepOutcome) {
        val r = resolver() ?: return
        val view = flight.toImpactFlight()
        when (outcome) {
            is ProjectileStepOutcome.HitEntity -> r.onHitEntity(view, outcome.target, outcome.at)
            is ProjectileStepOutcome.HitBlock -> r.onHitBlock(view, outcome.at)
            is ProjectileStepOutcome.Expired -> r.onExpire(view, outcome.at)
            ProjectileStepOutcome.Flying -> Unit
        }
    }

    /** Homing: bend the velocity toward the nearest valid target in range. */
    private fun steer(flight: ProjectileFlight, probe: ProjectileWorldProbe) {
        val m = flight.spec.motion as? MotionProfile.Homing ?: return
        val aim = ExecutionCosts.measure("target.homing", "unbound") {
            probe.homingTarget(flight, m.acquisitionRadius)
        } ?: return
        val speed = flight.velocity.length()
        val turn = m.turnRatePerTick.coerceIn(0.0, 1.0)
        val want = (aim - flight.position).normalize()
        val cur = flight.velocity.normalize()
        val blended = (cur * (1.0 - turn) + want * turn).normalize()
        if (blended.lengthSquared() > 0.0) flight.velocity = blended * speed
    }

    private fun applyForces(flight: ProjectileFlight) {
        when (val m = flight.spec.motion) {
            is MotionProfile.Linear -> flight.velocity = flight.velocity * (1.0 - m.drag)
            is MotionProfile.Ballistic ->
                flight.velocity = Vec3(flight.velocity.x, flight.velocity.y - m.gravity, flight.velocity.z) * (1.0 - m.drag)
            else -> Unit
        }
    }

    private fun isExpired(flight: ProjectileFlight, age: Long): Boolean = when (val l = flight.spec.lifetime) {
        is LifetimePolicy.Ticks -> age >= l.maxTicks
        is LifetimePolicy.Distance -> flight.travelled >= l.maxBlocks
        LifetimePolicy.UntilHit -> age >= UNTIL_HIT_CAP_TICKS
    } || flight.spec.motion is MotionProfile.Beam

    public companion object {
        public const val TICKS_PER_SECOND: Double = 20.0

        /** Range of an instant `Beam` projectile, in blocks. */
        public const val BEAM_RANGE: Double = 64.0

        /** `UntilHit` projectiles still despawn after this many ticks (1 minute). */
        public const val UNTIL_HIT_CAP_TICKS: Long = 1200L

        private const val EPS: Double = 1.0e-9
        private val DEFAULT_DIRECTION: Vec3 = Vec3(1.0, 0.0, 0.0)

        /** Blocks per tick for [motion] (`initialSpeed` is blocks per second). */
        public fun speedPerTick(motion: MotionProfile): Double =
            if (motion is MotionProfile.Beam) BEAM_RANGE else motion.initialSpeed / TICKS_PER_SECOND

        /** Hit-test inflation radius of [hit]. */
        public fun radiusOf(hit: HitProfile): Double = when (hit) {
            is HitProfile.Sphere -> hit.radius
            is HitProfile.Sweep -> hit.radius
            is HitProfile.Box -> hit.baked.enclosingRadius
        }

        /**
         * Slab test: parameter `t` in `[0, 1]` where the segment [from] -> [to] first enters the axis-aligned box
         * `[min, max]`, or `null` if it never does. A segment that starts inside the box returns `0.0`.
         */
        public fun segmentBoxEntry(from: Vec3, to: Vec3, min: Vec3, max: Vec3): Double? {
            var tMin = 0.0
            var tMax = 1.0
            val d = to - from
            val f = doubleArrayOf(from.x, from.y, from.z)
            val dv = doubleArrayOf(d.x, d.y, d.z)
            val lo = doubleArrayOf(min.x, min.y, min.z)
            val hi = doubleArrayOf(max.x, max.y, max.z)
            for (i in 0..2) {
                if (abs(dv[i]) < EPS) {
                    if (f[i] < lo[i] || f[i] > hi[i]) return null
                } else {
                    var t1 = (lo[i] - f[i]) / dv[i]
                    var t2 = (hi[i] - f[i]) / dv[i]
                    if (t1 > t2) {
                        val s = t1
                        t1 = t2
                        t2 = s
                    }
                    tMin = max(tMin, t1)
                    tMax = min(tMax, t2)
                    if (tMin > tMax) return null
                }
            }
            return tMin
        }
    }
}
