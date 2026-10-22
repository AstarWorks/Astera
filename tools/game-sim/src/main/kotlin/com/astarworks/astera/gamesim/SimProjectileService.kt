package com.astarworks.astera.gamesim

import com.astarworks.astera.combat.application.port.outbound.IProjectileHandle
import com.astarworks.astera.combat.application.port.outbound.IProjectileService
import com.astarworks.astera.combat.application.service.ProjectileContact
import com.astarworks.astera.combat.application.service.ProjectileFlight
import com.astarworks.astera.combat.application.service.ProjectileImpactResolver
import com.astarworks.astera.combat.application.service.ProjectileStepOutcome
import com.astarworks.astera.combat.application.service.ProjectileStepper
import com.astarworks.astera.combat.application.service.ProjectileWorldProbe
import com.astarworks.astera.combat.domain.projectile.ProjectileId
import com.astarworks.astera.combat.domain.projectile.ProjectileSpec
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.math.max

/**
 * Tick-stepped [IProjectileService] (the simulator's counterpart of
 * `PaperProjectileService`).
 *
 * Every tick each live projectile is advanced by the shared [ProjectileStepper] — the very
 * same stepping, expiry, trail and exactly-once [ProjectileImpactResolver] dispatch the Paper
 * service runs. Only the [ProjectileWorldProbe] differs: here a flat ground plane, placed
 * blocks and 0.6 x 1.8 entity boxes instead of Paper's ray traces.
 *
 * Approximations (documented in the README): entities are 0.6 x 1.8 boxes,
 * the world is a flat ground plane at [SimWorld.groundY] plus blocks placed
 * through the world gateway, `Beam` is an instant ray resolved on the next
 * stepped tick. Mobs are hit like players (their id is handed to the resolver
 * as the target).
 */
public class SimProjectileService(
    private val world: SimWorld,
    private val scheduler: IScheduler,
    private val recorder: TraceRecorder,
    private val isAlive: (PlayerId) -> Boolean,
    /** Resolved lazily: the resolver is built by the combat wiring after this service. */
    private val resolver: () -> ProjectileImpactResolver?,
    /** Trail particles (resolved lazily; `null` => no trail). */
    worldGateway: () -> IWorldGateway? = { null },
) : IProjectileService {

    private val stepper = ProjectileStepper(resolver)

    private val flights: MutableList<ProjectileFlight> = mutableListOf()

    private var stepTask: IScheduler.TaskHandle? = null

    /** Starts the per-tick stepper. Idempotent. */
    public fun start(): IScheduler.TaskHandle {
        stepTask?.cancel()
        return scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) { step() }.also { stepTask = it }
    }

    public fun stop() {
        stepTask?.cancel()
        stepTask = null
        flights.clear()
    }

    /** Positions of the projectiles currently in flight (for trace snapshots). */
    public fun snapshots(): List<TraceRecorder.ProjectileSnapshot> =
        flights.map { TraceRecorder.ProjectileSnapshot(it.id.toString(), it.owner, it.position) }

    public val inFlight: Int get() = flights.size

    override fun spawn(spec: ProjectileSpec, origin: Vec3, direction: Vec3, owner: PlayerId): IProjectileHandle {
        val flight = ProjectileFlight(
            id = ProjectileId.random(),
            owner = owner,
            spec = spec,
            position = origin,
            velocity = stepper.launchVelocity(spec, direction),
            spawnTick = scheduler.currentTick(),
        )
        flights += flight
        recorder.recordProjectileEvent(scheduler.currentTick(), flight.id.toString(), "spawn", origin, owner)
        return Handle(flight)
    }

    private inner class Handle(private val flight: ProjectileFlight) : IProjectileHandle {
        override val id: ProjectileId get() = flight.id
        override val isAlive: Boolean get() = flights.any { it === flight }
        override val position: Vec3? get() = flight.position
        override fun cancel() {
            if (!flights.remove(flight)) return
            recorder.recordProjectileEvent(
                scheduler.currentTick(), flight.id.toString(), "cancel", flight.position, flight.owner,
            )
        }
    }

    private fun step() {
        val now = scheduler.currentTick()
        for (f in flights.toList()) {
            if (flights.none { it === f }) continue // cancelled by an earlier impact this tick
            stepper.step(f, now, probe) { terminal ->
                flights.remove(f)
                when (terminal) {
                    is ProjectileStepOutcome.HitEntity ->
                        recorder.recordProjectileEvent(now, f.id.toString(), "hit_entity", terminal.at, f.owner, terminal.target)
                    is ProjectileStepOutcome.HitBlock ->
                        recorder.recordProjectileEvent(now, f.id.toString(), "hit_block", terminal.at, f.owner)
                    is ProjectileStepOutcome.Expired ->
                        recorder.recordProjectileEvent(now, f.id.toString(), "expire", terminal.at, f.owner)
                    ProjectileStepOutcome.Flying -> Unit
                }
            }
        }
    }

    private val probe = object : ProjectileWorldProbe {
        override fun firstContact(flight: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact? =
            this@SimProjectileService.firstContact(flight, from, to)

        /** Nearest living non-owner entity in range, aimed at its chest. */
        override fun homingTarget(flight: ProjectileFlight, radius: Double): Vec3? =
            world.entities
                .filter { it.id != flight.owner && isAlive(it.id) && it.position.distance(flight.position) <= radius }
                .minByOrNull { it.position.distanceSquared(flight.position) }
                ?.let { it.position + Vec3(0.0, it.hitboxHeight / 2, 0.0) }
    }

    /** Earliest entity or block contact along `from -> to`, or null. */
    private fun firstContact(f: ProjectileFlight, from: Vec3, to: Vec3): ProjectileContact? {
        val reach = ProjectileStepper.radiusOf(f.spec.hit)
        var best: ProjectileContact? = null
        var bestT = Double.MAX_VALUE
        for (e in world.entities) {
            if (e.id == f.owner || !isAlive(e.id)) continue
            val t = ProjectileStepper.segmentBoxEntry(
                from, to,
                Vec3(e.position.x - e.hitboxWidth / 2 - reach, e.position.y - reach, e.position.z - e.hitboxWidth / 2 - reach),
                Vec3(e.position.x + e.hitboxWidth / 2 + reach, e.position.y + e.hitboxHeight + reach, e.position.z + e.hitboxWidth / 2 + reach),
            ) ?: continue
            if (t < bestT) {
                bestT = t
                best = ProjectileContact.Entity(e.id, from + (to - from) * t)
            }
        }
        // Ground plane.
        if (to.y <= world.groundY && from.y > world.groundY) {
            val t = (from.y - world.groundY) / (from.y - to.y)
            if (t < bestT) {
                bestT = t
                best = ProjectileContact.Block(from + (to - from) * t)
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
                best = ProjectileContact.Block(p)
                break
            }
        }
        return best
    }

    private companion object {
        const val BLOCK_SAMPLE_STEP: Double = 0.25
        val DEFAULT_WORLD = WorldId("world")
    }
}
