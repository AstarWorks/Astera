package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IProjectileHandle
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.combat.domain.projectile.HitProfile
import com.astarworks.astera.combat.domain.projectile.LifetimePolicy
import com.astarworks.astera.combat.domain.projectile.MotionProfile
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import kotlin.math.max
import kotlin.math.min

/**
 * Tick-stepped [IProjectileService] (the simulator's counterpart of
 * `PaperProjectileService` + `ProjectileHitListener`).
 *
 * Every tick, each live projectile moves by its velocity (blocks per tick:
 * `MotionProfile.initialSpeed` / 20, as the Paper service converts it), is tested against the segment it swept, and
 * on the first contact the shared [ProjectileImpactResolver] is called
 * **exactly once** (`onHitEntity` / `onHitBlock` / `onExpire`) before the
 * projectile is removed — the same dispatch the Paper listener performs.
 *
 * Approximations (documented in the README): entities are 0.6 x 1.8 boxes,
 * the world is a flat ground plane at [SimWorld.groundY] plus blocks placed
 * through the world gateway, `Curved` flies like `Linear`, `Beam` is an
 * instant ray resolved on the next stepped tick. Mobs are hit like players
 * (their id is handed to the resolver as the target).
 */
public class SimProjectileService(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val recorder: TraceRecorder,
    private val isAlive: (PlayerId) -> Boolean,
    /** Resolved lazily: the resolver is built by the combat wiring after this service. */
    private val resolver: () -> ProjectileImpactResolver?,
) : IProjectileService {

    private class Flight(
        val id: ProjectileId,
        val owner: PlayerId,
        val spec: ProjectileSpec,
        var position: Vec3,
        var velocity: Vec3,
        val spawnTick: Long,
        var travelled: Double = 0.0,
        var alive: Boolean = true,
    )

    private val flights: MutableList<Flight> = mutableListOf()

    private var stepper: IScheduler.TaskHandle? = null

    /** Starts the per-tick stepper. Idempotent. */
    public fun start(): IScheduler.TaskHandle {
        stepper?.cancel()
        return scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) { step() }.also { stepper = it }
    }

    public fun stop() {
        stepper?.cancel()
        stepper = null
        flights.clear()
    }

    /** Positions of the projectiles currently in flight (for trace snapshots). */
    public fun snapshots(): List<TraceRecorder.ProjectileSnapshot> =
        flights.filter { it.alive }.map { TraceRecorder.ProjectileSnapshot(it.id.toString(), it.owner, it.position) }

    public val inFlight: Int get() = flights.count { it.alive }

    override fun spawn(spec: ProjectileSpec, origin: Vec3, direction: Vec3, owner: PlayerId): IProjectileHandle {
        val dir = direction.normalize().takeIf { it.lengthSquared() > 0.0 } ?: Vec3(1.0, 0.0, 0.0)
        // MotionProfile.initialSpeed is blocks per SECOND (ProjectileHandler multiplies the
        // YAML blocks/tick launch_speed by 20); PaperProjectileService divides it back.
        val speed = if (spec.motion is MotionProfile.Beam) BEAM_RANGE else spec.motion.initialSpeed / TICKS_PER_SECOND
        val flight = Flight(
            id = ProjectileId.random(),
            owner = owner,
            spec = spec,
            position = origin,
            velocity = dir * speed,
            spawnTick = scheduler.currentTick(),
        )
        flights += flight
        recorder.recordProjectileEvent(scheduler.currentTick(), flight.id.toString(), "spawn", origin, owner)
        return Handle(flight)
    }

    private inner class Handle(private val flight: Flight) : IProjectileHandle {
        override val id: ProjectileId get() = flight.id
        override val isAlive: Boolean get() = flight.alive
        override fun cancel() {
            if (!flight.alive) return
            flight.alive = false
            recorder.recordProjectileEvent(
                scheduler.currentTick(), flight.id.toString(), "cancel", flight.position, flight.owner,
            )
        }
    }

    private fun step() {
        val now = scheduler.currentTick()
        for (f in flights.toList()) {
            if (!f.alive) continue
            val age = now - f.spawnTick
            steer(f)
            val from = f.position
            val to = from + f.velocity
            val contact = firstContact(f, from, to)
            val flightView = ProjectileImpactResolver.Flight(f.id, f.owner, f.spec)
            when (contact) {
                is Contact.Entity -> {
                    f.position = contact.at
                    finish(f, "hit_entity", contact.at, contact.id)
                    resolver()?.onHitEntity(flightView, contact.id, contact.at)
                }
                is Contact.Block -> {
                    f.position = contact.at
                    finish(f, "hit_block", contact.at, null)
                    resolver()?.onHitBlock(flightView, contact.at)
                }
                null -> {
                    f.travelled += from.distance(to)
                    f.position = to
                    applyForces(f)
                    if (expired(f, age)) {
                        finish(f, "expire", to, null)
                        resolver()?.onExpire(flightView, to)
                    }
                }
            }
        }
        flights.removeAll { !it.alive }
    }

    private fun finish(f: Flight, kind: String, at: Vec3, target: PlayerId?) {
        f.alive = false
        recorder.recordProjectileEvent(scheduler.currentTick(), f.id.toString(), kind, at, f.owner, target)
    }

    private fun expired(f: Flight, age: Long): Boolean = when (val l = f.spec.lifetime) {
        is LifetimePolicy.Ticks -> age >= l.maxTicks
        is LifetimePolicy.Distance -> f.travelled >= l.maxBlocks
        LifetimePolicy.UntilHit -> age >= UNTIL_HIT_CAP_TICKS
    } || f.spec.motion is MotionProfile.Beam

    /** Homing: bend the velocity toward the nearest living non-owner entity in range. */
    private fun steer(f: Flight) {
        val m = f.spec.motion as? MotionProfile.Homing ?: return
        val target = world.entities
            .filter { it.id != f.owner && isAlive(it.id) && it.position.distance(f.position) <= m.acquisitionRadius }
            .minByOrNull { it.position.distanceSquared(f.position) } ?: return
        val speed = f.velocity.length()
        val want = ((target.position + Vec3(0.0, target.hitboxHeight / 2, 0.0)) - f.position).normalize()
        val cur = f.velocity.normalize()
        val blended = (cur * (1.0 - m.turnRatePerTick.coerceIn(0.0, 1.0)) + want * m.turnRatePerTick.coerceIn(0.0, 1.0))
            .normalize()
        if (blended.lengthSquared() > 0.0) f.velocity = blended * speed
    }

    private fun applyForces(f: Flight) {
        when (val m = f.spec.motion) {
            is MotionProfile.Linear -> f.velocity = f.velocity * (1.0 - m.drag)
            is MotionProfile.Ballistic ->
                f.velocity = Vec3(f.velocity.x, f.velocity.y - m.gravity, f.velocity.z) * (1.0 - m.drag)
            else -> Unit
        }
    }

    private sealed interface Contact {
        val at: Vec3
        data class Entity(val id: PlayerId, override val at: Vec3) : Contact
        data class Block(override val at: Vec3) : Contact
    }

    /** Earliest entity or block contact along `from -> to`, or null. */
    private fun firstContact(f: Flight, from: Vec3, to: Vec3): Contact? {
        val reach = radiusOf(f.spec.hit)
        var best: Contact? = null
        var bestT = Double.MAX_VALUE
        for (e in world.entities) {
            if (e.id == f.owner || !isAlive(e.id)) continue
            val t = segmentBoxEntry(
                from, to,
                Vec3(e.position.x - e.hitboxWidth / 2 - reach, e.position.y - reach, e.position.z - e.hitboxWidth / 2 - reach),
                Vec3(e.position.x + e.hitboxWidth / 2 + reach, e.position.y + e.hitboxHeight + reach, e.position.z + e.hitboxWidth / 2 + reach),
            ) ?: continue
            if (t < bestT) {
                bestT = t
                best = Contact.Entity(e.id, from + (to - from) * t)
            }
        }
        // Ground plane.
        if (to.y <= world.groundY && from.y > world.groundY) {
            val t = (from.y - world.groundY) / (from.y - to.y)
            if (t < bestT) {
                bestT = t
                best = Contact.Block(from + (to - from) * t)
            }
        }
        // Placed blocks: sample the segment.
        val len = from.distance(to)
        val samples = max(1, (len / BLOCK_SAMPLE_STEP).toInt())
        for (i in 1..samples) {
            val t = i.toDouble() / samples
            if (t >= bestT) break
            val p = from + (to - from) * t
            if (world.blockAt(world.find(f.owner)?.world ?: DEFAULT_WORLD, p) != null) {
                bestT = t
                best = Contact.Block(p)
                break
            }
        }
        return best
    }

    private fun radiusOf(hit: HitProfile): Double = when (hit) {
        is HitProfile.Sphere -> hit.radius
        is HitProfile.Sweep -> hit.radius
        is HitProfile.Box -> max(hit.width, max(hit.height, hit.depth)) / 2.0
    }

    /** Slab test: parameter `t` in [0,1] where the segment first enters the box, or null. */
    private fun segmentBoxEntry(from: Vec3, to: Vec3, min: Vec3, max: Vec3): Double? {
        var tMin = 0.0
        var tMax = 1.0
        val d = to - from
        val f = doubleArrayOf(from.x, from.y, from.z)
        val dv = doubleArrayOf(d.x, d.y, d.z)
        val lo = doubleArrayOf(min.x, min.y, min.z)
        val hi = doubleArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (kotlin.math.abs(dv[i]) < EPS) {
                if (f[i] < lo[i] || f[i] > hi[i]) return null
            } else {
                var t1 = (lo[i] - f[i]) / dv[i]
                var t2 = (hi[i] - f[i]) / dv[i]
                if (t1 > t2) { val s = t1; t1 = t2; t2 = s }
                tMin = max(tMin, t1)
                tMax = min(tMax, t2)
                if (tMin > tMax) return null
            }
        }
        return tMin
    }

    private companion object {
        const val TICKS_PER_SECOND: Double = 20.0
        const val BEAM_RANGE: Double = 64.0
        const val UNTIL_HIT_CAP_TICKS: Long = 1200L
        const val BLOCK_SAMPLE_STEP: Double = 0.25
        const val EPS: Double = 1.0e-9
        val DEFAULT_WORLD = com.astarworks.astera.core.domain.world.WorldId("world")
    }
}
