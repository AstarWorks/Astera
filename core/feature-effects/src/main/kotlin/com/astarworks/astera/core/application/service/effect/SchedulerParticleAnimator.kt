package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.regionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.effect.IParticleAnimator
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.domain.effect.EffectHandle
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimationSession
import com.astarworks.astera.core.domain.effect.animation.PlannedEmission
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import kotlin.random.Random

/**
 * Default [IParticleAnimator] (ADR-0196): expands the animation through
 * [ParticleAnimationSession] and spawns each [PlannedEmission] through
 * [IWorldGateway.spawnParticle], one scheduler task per distinct tick (a 60-point
 * ring is 2 tasks, not 60). Offset-0 emissions fire synchronously so a cast burst
 * appears on the cast tick.
 */
public class SchedulerParticleAnimator(
    private val scheduler: IScheduler,
    private val worldGateway: IWorldGateway,
    private val random: () -> Random = { Random.Default },
) : IParticleAnimator {

    override fun open(world: WorldId, animation: ParticleAnimation): IParticleAnimator.Player =
        PlayerImpl(world, ParticleAnimationSession(animation, random()))

    private inner class PlayerImpl(
        private val world: WorldId,
        private val session: ParticleAnimationSession,
    ) : IParticleAnimator.Player {
        private val pending = ArrayList<IScheduler.TaskHandle>()
        private var cancelled = false

        override fun play(origin: Vec3, direction: Vec3): EffectHandle {
            if (cancelled) return EffectHandle.NONE
            val group = Group()
            for ((tick, emissions) in session.plan(origin, direction).groupBy { it.tickOffset }) {
                if (tick <= 0) {
                    emit(emissions)
                } else {
                    val task = scheduler.run(regionScope(world, origin), ticks(tick.toLong())) {
                        if (group.isActive && !cancelled) emit(emissions)
                    }
                    group.tasks += task
                    pending += task
                }
            }
            pending.removeAll { it.isCancelled }
            return group
        }

        override fun cancel() {
            cancelled = true
            pending.forEach { it.cancel() }
            pending.clear()
        }

        private fun emit(emissions: List<PlannedEmission>) {
            for (e in emissions) worldGateway.spawnParticle(world, e.position, e.particle)
        }
    }

    private class Group : EffectHandle {
        val tasks: MutableList<IScheduler.TaskHandle> = ArrayList()
        private var cancelled = false

        override fun cancel() {
            cancelled = true
            tasks.forEach { it.cancel() }
        }

        override val isActive: Boolean
            get() = !cancelled && tasks.any { !it.isCancelled }
    }
}
