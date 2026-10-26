package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.ISoundScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.world.WorldId
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Default in-memory [ISoundScheduler]. Wave 5effects (ADR-0109).
 *
 * Drives every [ScheduledSoundSpec] variant through [IScheduler] and
 * routes individual plays through [IWorldGateway.playSound]. Same
 * cancellation semantics as `InMemoryParticleScheduler`: composite
 * handles tear down every still-pending iteration on `cancel()`.
 */
public class InMemorySoundScheduler(
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val log: Logger = LoggerFactory.getLogger(InMemorySoundScheduler::class.java),
) : ISoundScheduler {

    override fun schedule(spec: ScheduledSoundSpec): EffectHandle {
        val world = spec.world ?: run {
            log.warn("ScheduledSoundSpec {} has no world; dropping", spec::class.simpleName)
            return EffectHandle.NONE
        }
        return when (spec) {
            is ScheduledSoundSpec.EmitOnce -> scheduleOnce(spec, world)
            is ScheduledSoundSpec.Delayed -> scheduleDelayed(spec, world)
            is ScheduledSoundSpec.Loop -> scheduleLoop(spec, world)
            is ScheduledSoundSpec.Sequence -> scheduleSequence(spec, world)
        }
    }

    private fun scheduleOnce(spec: ScheduledSoundSpec.EmitOnce, world: WorldId): EffectHandle {
        val task = scheduler.run(regionScope(world, spec.position), ticks((spec.atTick - scheduler.currentTick()).coerceAtLeast(0))) {
            worldGateway.playSound(world, spec.position, spec.sound)
        }
        return TaskBackedHandle(task)
    }

    private fun scheduleDelayed(spec: ScheduledSoundSpec.Delayed, world: WorldId): EffectHandle {
        val task = scheduler.run(regionScope(world, spec.position), ticks(spec.delayTicks)) {
            worldGateway.playSound(world, spec.position, spec.sound)
        }
        return TaskBackedHandle(task)
    }

    private fun scheduleLoop(spec: ScheduledSoundSpec.Loop, world: WorldId): EffectHandle {
        val composite = CompositeHandle()
        val period = scheduler.repeat(regionScope(world, spec.position),
            initialDelay = ticks(spec.initialDelayTicks),
            period = ticks(spec.intervalTicks),
        ) {
            if (composite.isActive) {
                worldGateway.playSound(world, spec.position, spec.sound)
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

    private fun scheduleSequence(spec: ScheduledSoundSpec.Sequence, world: WorldId): EffectHandle {
        val composite = CompositeHandle()
        for (step in spec.steps) {
            val task = scheduler.run(regionScope(world, step.position), ticks(step.atTick)) {
                if (composite.isActive) {
                    worldGateway.playSound(world, step.position, step.sound)
                }
            }
            composite.add(task)
        }
        val lastTick = spec.steps.maxOf { it.atTick }
        val complete = scheduler.run(ExecutionScope.Global, ticks(lastTick + 1)) { composite.markComplete() }
        composite.add(complete)
        return composite
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
