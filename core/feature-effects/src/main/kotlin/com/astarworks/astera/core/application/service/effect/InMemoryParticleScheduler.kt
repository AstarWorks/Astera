package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IParticleScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Default in-memory [IParticleScheduler]. Wave 5effects (ADR-0109).
 *
 * Drives every [ScheduledParticleSpec] variant through [IScheduler] and
 * fires individual emissions through [IWorldGateway.spawnParticle]. The
 * gateway's null-implementation will warn-log under test; in production
 * the Paper adapter materializes each call into `World.spawnParticle`.
 *
 * # Cancellation
 *
 *  - `EmitOnce` + `Delayed`: cancelling drops the pending fire.
 *  - `Loop` + `Burst` + `Pulse` + `RingExpansion`: cancelling tears down
 *    every pending iteration. The handle's `isActive` flips to `false`
 *    on either explicit cancel or natural completion (last duration tick).
 *
 * # Pulse / RingExpansion approximation
 *
 * Both compute the **ring** sample points as `count` evenly-spaced
 * points on a circle on the XZ plane. Vertical orientation is the
 * common case for boss-phase shock-waves and AOE tells; if a caller
 * needs a sphere or arbitrary plane, they should emit several
 * `RingExpansion` chained via [com.astarworks.astera.core.application.port.outbound.effect.IEffectOrchestrator]
 * `Parallel`.
 */
public class InMemoryParticleScheduler(
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val log: Logger = LoggerFactory.getLogger(InMemoryParticleScheduler::class.java),
) : IParticleScheduler {

    override fun schedule(spec: ScheduledParticleSpec): EffectHandle {
        val world = spec.world ?: run {
            log.warn("ScheduledParticleSpec {} has no world; dropping (Phase 1 default world resolution deferred)", spec::class.simpleName)
            return EffectHandle.NONE
        }
        return when (spec) {
            is ScheduledParticleSpec.EmitOnce -> scheduleOnce(spec, world)
            is ScheduledParticleSpec.Loop -> scheduleLoop(spec, world)
            is ScheduledParticleSpec.Burst -> scheduleBurst(spec, world)
            is ScheduledParticleSpec.Pulse -> schedulePulse(spec, world)
            is ScheduledParticleSpec.RingExpansion -> scheduleRingExpansion(spec, world)
        }
    }

    // ----- variants ----------------------------------------------------

    private fun scheduleOnce(spec: ScheduledParticleSpec.EmitOnce, world: WorldId): EffectHandle {
        val handle = scheduler.run(regionScope(world, spec.position), ticks((spec.atTick - scheduler.currentTick()).coerceAtLeast(0))) {
            worldGateway.spawnParticle(world, spec.position, spec.particle)
        }
        return TaskBackedHandle(handle)
    }

    private fun scheduleLoop(spec: ScheduledParticleSpec.Loop, world: WorldId): EffectHandle {
        val composite = CompositeHandle()
        val period = scheduler.repeat(regionScope(world, spec.position),
            initialDelay = ticks(spec.initialDelayTicks),
            period = ticks(spec.intervalTicks),
        ) {
            if (composite.isActive) {
                worldGateway.spawnParticle(world, spec.position, spec.particle)
            }
        }
        composite.add(period)
        if (spec.durationTicks > 0) {
            val total = spec.initialDelayTicks + spec.durationTicks
            val cancel = scheduler.run(regionScope(world, spec.position), ticks(total)) { composite.cancel() }
            composite.add(cancel)
        }
        return composite
    }

    private fun scheduleBurst(spec: ScheduledParticleSpec.Burst, world: WorldId): EffectHandle {
        val composite = CompositeHandle()
        val per = if (spec.spreadTicks == 0L || spec.count == 1) 0L else spec.spreadTicks / spec.count
        repeat(spec.count) { i ->
            val delay = spec.initialDelayTicks + per * i
            val task = scheduler.run(regionScope(world, spec.position), ticks(delay)) {
                if (composite.isActive) {
                    worldGateway.spawnParticle(world, spec.position, spec.particle)
                }
            }
            composite.add(task)
        }
        // Self-complete after the last fire.
        val complete = scheduler.run(regionScope(world, spec.position),
            ticks(spec.initialDelayTicks + max(per * spec.count, 0L)),
        ) { composite.markComplete() }
        composite.add(complete)
        return composite
    }

    private fun schedulePulse(spec: ScheduledParticleSpec.Pulse, world: WorldId): EffectHandle {
        val composite = CompositeHandle()
        val steps = spec.durationTicks / spec.stepTicks
        for (i in 0..steps) {
            val frac = if (steps == 0L) 0.0 else i.toDouble() / steps.toDouble()
            val radius = spec.fromRadius + (spec.toRadius - spec.fromRadius) * frac
            val delay = spec.initialDelayTicks + i * spec.stepTicks
            val task = scheduler.run(regionScope(world, spec.center), ticks(delay)) {
                if (composite.isActive) {
                    emitRing(world, spec.center, radius, spec.particle)
                }
            }
            composite.add(task)
        }
        val complete = scheduler.run(regionScope(world, spec.center),
            ticks(spec.initialDelayTicks + spec.durationTicks),
        ) { composite.markComplete() }
        composite.add(complete)
        return composite
    }

    private fun scheduleRingExpansion(
        spec: ScheduledParticleSpec.RingExpansion,
        world: WorldId,
    ): EffectHandle {
        val composite = CompositeHandle()
        val steps = spec.durationTicks / spec.stepTicks
        for (i in 0..steps) {
            val radius = spec.initialRadius + spec.expandRate * (i * spec.stepTicks)
            val delay = spec.initialDelayTicks + i * spec.stepTicks
            val task = scheduler.run(regionScope(world, spec.center), ticks(delay)) {
                if (composite.isActive) {
                    emitRing(world, spec.center, radius, spec.particle)
                }
            }
            composite.add(task)
        }
        val complete = scheduler.run(regionScope(world, spec.center),
            ticks(spec.initialDelayTicks + spec.durationTicks),
        ) { composite.markComplete() }
        composite.add(complete)
        return composite
    }

    /**
     * Sample a ring of points on the XZ plane at [radius] around [center]
     * and fire one [ParticleSpec.count]-sized emission per sample. We use
     * `particle.count` as the **per-sample** count so the caller controls
     * density per ring point.
     */
    private fun emitRing(world: WorldId, center: Vec3, radius: Double, particle: ParticleSpec) {
        val samples = max(8, particle.count.coerceAtMost(64))
        for (i in 0 until samples) {
            val theta = 2.0 * Math.PI * i.toDouble() / samples.toDouble()
            val pos = Vec3(
                center.x + radius * cos(theta),
                center.y,
                center.z + radius * sin(theta),
            )
            worldGateway.spawnParticle(world, pos, particle)
        }
    }

    // ----- handles -----------------------------------------------------

    private class TaskBackedHandle(private val task: IScheduler.TaskHandle) : EffectHandle {
        override fun cancel() {
            task.cancel()
        }
        override val isActive: Boolean
            get() = !task.isCancelled
    }

    private class CompositeHandle : EffectHandle {
        private val children: MutableList<IScheduler.TaskHandle> = mutableListOf()
        private var cancelled: Boolean = false
        private var complete: Boolean = false

        fun add(task: IScheduler.TaskHandle) {
            if (cancelled) {
                task.cancel()
                return
            }
            children += task
        }

        fun markComplete() {
            if (cancelled) return
            complete = true
        }

        override fun cancel() {
            if (cancelled) return
            cancelled = true
            children.forEach { it.cancel() }
        }

        override val isActive: Boolean
            get() = !cancelled && !complete
    }
}
