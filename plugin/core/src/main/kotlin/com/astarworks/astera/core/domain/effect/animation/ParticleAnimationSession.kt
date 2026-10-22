package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.random.Random

/** One spawn the animation wants [tickOffset] ticks after [ParticleAnimationSession.plan] was called. */
public data class PlannedEmission(
    val tickOffset: Int,
    val position: Vec3,
    val particle: ParticleSpec,
)

/**
 * Pure expansion of a [ParticleAnimation] into timed spawns (ADR-0194; the timing
 * semantics of RTM's `ParticleServiceImpl.processParticleSet` + `AbstractSimpleScheduler`).
 *
 * A session owns the per-instance [CircleCursor]s, keyed by `(shape, instance)` like
 * RTM's `IParticleScheduler.getData`, so calling [plan] repeatedly (once per projectile
 * tick) continues each orbit. A fresh session per play of a one-shot animation gives
 * each play its own random orbit.
 *
 * Timing, per shape and instance (`amount` of them):
 *  - [AnimatedShape.Point]: each particle spawns once at `shape.delay + particle.delay`.
 *  - [AnimatedShape.Circle]: for `count` in `0 until period` and each particle, a task at
 *    `shape.delay + particle.delay + count` drops `acceleration` points. Tasks of one
 *    instance run in ascending tick order (RTM's stable sort), the order the shared
 *    cursor advances in.
 */
public class ParticleAnimationSession(
    public val animation: ParticleAnimation,
    private val random: Random = Random.Default,
) {
    private val cursors: MutableMap<Pair<Int, Int>, CircleCursor> = HashMap()

    /** Expand the animation anchored at [origin] facing [direction]; sorted by tick (stable). */
    public fun plan(origin: Vec3, direction: Vec3): List<PlannedEmission> {
        val out = ArrayList<PlannedEmission>()
        animation.shapes.forEachIndexed { shapeIndex, shape ->
            for (instance in 0 until shape.amount) {
                when (shape) {
                    is AnimatedShape.Point -> planPoint(shape, origin, direction, out)
                    is AnimatedShape.Circle -> planCircle(shape, shapeIndex, instance, origin, direction, out)
                }
            }
        }
        return out.sortedBy { it.tickOffset }
    }

    private fun planPoint(shape: AnimatedShape.Point, origin: Vec3, direction: Vec3, out: MutableList<PlannedEmission>) {
        val dir = shape.angle.resolve(direction, random)
        for (p in shape.particles) {
            out += PlannedEmission(shape.delayTicks + p.delayTicks, origin, p.toSpec(dir))
        }
    }

    private fun planCircle(
        shape: AnimatedShape.Circle,
        shapeIndex: Int,
        instance: Int,
        origin: Vec3,
        direction: Vec3,
        out: MutableList<PlannedEmission>,
    ) {
        val cursor = cursors.getOrPut(shapeIndex to instance) {
            CircleCursor(shape, shape.angle.resolve(direction, random), random)
        }
        // Tasks of this instance in RTM order: count-major, particle-minor, then stable by tick.
        val tasks = ArrayList<Pair<Int, AnimatedParticle>>()
        for (count in 0 until shape.periodTicks) {
            for (p in shape.particles) tasks += (shape.delayTicks + p.delayTicks + count) to p
        }
        for ((tick, p) in tasks.sortedBy { it.first }) {
            repeat(shape.acceleration) {
                val point = cursor.nextPoint(origin)
                val extraVector = if (p.count == 0) (point - origin).normalize() else cursor.u
                out += PlannedEmission(tick, point, p.toSpec(extraVector))
            }
        }
    }
}
