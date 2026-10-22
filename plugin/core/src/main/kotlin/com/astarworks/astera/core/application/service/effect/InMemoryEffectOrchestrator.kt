package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier
import com.astarworks.astera.core.application.port.outbound.effect.IEffectOrchestrator
import com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.chain.EffectChain
import com.astarworks.astera.core.domain.effect.chain.EffectStep
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Default in-memory [IEffectOrchestrator]. Wave 5effects (ADR-0109).
 *
 * Walks the [EffectChain] tree and dispatches each [EffectStep] through
 * the appropriate outbound port:
 *
 *  - `ParticleStep` → [IWorldGateway.spawnParticle]
 *  - `SoundStep`    → [IWorldGateway.playSound]
 *  - `StatusEffectStep` → [IStatusEffectApplier] (combat-side bridge)
 *  - `DamageStep`   → [IDamageApplier] (combat-side bridge)
 *  - `DelayStep`    → [IScheduler.runLater] (gap inside a Sequence)
 *
 * # Composition
 *
 *  - `EmitOnce`: single step, fires on next dispatch.
 *  - `Sequence`: steps run in order; `DelayStep` separates them in time.
 *  - `Parallel`: each branch runs concurrently; the composite is active
 *    while any branch is.
 *  - `Loop`: replay `chain` until `durationTicks` elapses.
 *
 * # Cross-plugin bridge
 *
 * Combat registers `IStatusEffectApplier` / `IDamageApplier` at
 * priority 100 in `AsteraCombatPlugin.onEnable()`. Core registers
 * no-op defaults at priority 0 so the orchestrator never NPEs when
 * combat is unloaded (or in a unit test without combat on the
 * classpath); the no-op default warn-logs once per call.
 */
public class InMemoryEffectOrchestrator(
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val statusApplier: IStatusEffectApplier,
    private val damageApplier: IDamageApplier,
    private val log: Logger = LoggerFactory.getLogger(InMemoryEffectOrchestrator::class.java),
) : IEffectOrchestrator {

    override fun play(chain: EffectChain): EffectHandle {
        val composite = CompositeHandle()
        val endTick = execute(chain, composite, delayTicks = 0L)
        composite.add(scheduler.runLater(ticks(endTick)) { composite.markComplete() })
        return composite
    }

    /** Schedule every branch before completing the shared handle at the latest end tick. */
    private fun execute(chain: EffectChain, composite: CompositeHandle, delayTicks: Long): Long =
        when (chain) {
            is EffectChain.EmitOnce -> {
                dispatchStep(chain.step, composite, delayTicks)
                delayTicks + ((chain.step as? EffectStep.DelayStep)?.durationTicks ?: 0L)
            }
            is EffectChain.Sequence -> {
                var cursor = delayTicks
                for (step in chain.steps) {
                    if (step is EffectStep.DelayStep) {
                        cursor += step.durationTicks
                    } else {
                        dispatchStep(step, composite, cursor)
                    }
                }
                cursor
            }
            is EffectChain.Parallel -> chain.branches.maxOf { execute(it, composite, delayTicks) }
            is EffectChain.Loop -> {
                if (chain.durationTicks == 0L) {
                    log.warn("EffectChain.Loop with durationTicks=0 dropped (infinite loop guard)")
                    delayTicks
                } else {
                    val iterations = chain.durationTicks / chain.intervalTicks
                    (0..iterations).maxOf { i ->
                        execute(chain.chain, composite, delayTicks + i * chain.intervalTicks)
                    }
                }
            }
        }

    private fun dispatchStep(step: EffectStep, composite: CompositeHandle, delayTicks: Long) {
        val task = scheduler.runLater(ticks(delayTicks)) {
            if (!composite.isActive) return@runLater
            executeOne(step)
        }
        composite.add(task)
    }

    private fun executeOne(step: EffectStep) {
        when (step) {
            is EffectStep.ParticleStep -> {
                val world = step.world ?: run {
                    log.warn("ParticleStep dropped: world is null")
                    return
                }
                worldGateway.spawnParticle(world, step.position, step.particle)
            }
            is EffectStep.SoundStep -> {
                val world = step.world ?: run {
                    log.warn("SoundStep dropped: world is null")
                    return
                }
                worldGateway.playSound(world, step.position, step.sound)
            }
            is EffectStep.StatusEffectStep -> {
                val target = step.target ?: run {
                    log.warn("StatusEffectStep dropped: target is null")
                    return
                }
                statusApplier.apply(target, step.effectId, step.durationTicks)
            }
            is EffectStep.DamageStep -> {
                val target = step.target ?: run {
                    log.warn("DamageStep dropped: target is null")
                    return
                }
                damageApplier.apply(target, step.profile)
            }
            is EffectStep.DelayStep -> {
                // Delay handled in Sequence walk; appearing here means
                // someone wrapped a DelayStep in EmitOnce / Parallel.
                // Treat as a no-op so the orchestrator stays exhaustive.
            }
        }
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
