package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** One spawn the animation wants [tickOffset] ticks after [ParticleAnimationSession.plan] was called. */
public data class PlannedEmission(
    val tickOffset: Int,
    val position: Vec3,
    val particle: ParticleSpec,
)

/**
 * Pure expansion of a [ParticleAnimation] into timed spawns (ADR-0196; the timing
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
 *  - [AnimatedShape.Ring]: `points` per tick for `periodTicks` ticks, radius growing start to end.
 *  - [AnimatedShape.Line]: the whole jagged polyline at `shape.delay + particle.delay`.
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
                    is AnimatedShape.Arc -> planArc(shape, origin, direction, out)
                    is AnimatedShape.Ring -> planRing(shape, origin, direction, out)
                    is AnimatedShape.Line -> planLine(shape, origin, direction, out)
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

    private fun planArc(shape: AnimatedShape.Arc, origin: Vec3, direction: Vec3, out: MutableList<PlannedEmission>) {
        val flat = Vec3(direction.x, 0.0, direction.z)
        val forward = if (flat.lengthSquared() > 1e-9) flat.normalize() else Vec3(1.0, 0.0, 0.0)
        val right = Vec3(-forward.z, 0.0, forward.x) // forward rotated -90 degrees about Y (looking along +z, right is -x)
        val last = (shape.points - 1).coerceAtLeast(1)
        for (i in 0 until shape.points) {
            val k = if (shape.points == 1) 0.5 else i.toDouble() / last
            val theta = Math.toRadians(shape.startDegrees + (shape.endDegrees - shape.startDegrees) * k)
            val radial = forward * kotlin.math.cos(theta) + right * kotlin.math.sin(theta)
            val height = shape.heightStart + (shape.heightEnd - shape.heightStart) * k
            val position = origin + radial * shape.radius + Vec3(0.0, height, 0.0)
            val tick = shape.delayTicks + Math.round(shape.sweepTicks * k).toInt()
            // tangent of travel (toward increasing k), used as the direction of count == 0 particles
            val sign = if (shape.endDegrees >= shape.startDegrees) 1.0 else -1.0
            val tangent = (forward * -kotlin.math.sin(theta) + right * kotlin.math.cos(theta)) * sign
            for (p in shape.particles) out += PlannedEmission(tick + p.delayTicks, position, p.toSpec(tangent))
        }
    }

    private fun planRing(shape: AnimatedShape.Ring, origin: Vec3, direction: Vec3, out: MutableList<PlannedEmission>) {
        val basis = PlaneBasis(shape.angle.resolve(direction, random))
        val phase = random.nextDouble(0.0, 2 * PI)
        for (step in 0 until shape.periodTicks) {
            val t = if (shape.periodTicks == 1) 1.0 else step.toDouble() / (shape.periodTicks - 1)
            val radius = shape.startRadius + (shape.endRadius - shape.startRadius) * t
            for (i in 0 until shape.points) {
                val rad = phase + 2 * PI * i / shape.points
                val outward = basis.a * cos(rad) + basis.b * sin(rad)
                val position = origin + outward * radius + basis.axis * (shape.rise * t)
                for (p in shape.particles) {
                    out += PlannedEmission(shape.delayTicks + p.delayTicks + step, position, p.toSpec(outward))
                }
            }
        }
    }

    private fun planLine(shape: AnimatedShape.Line, origin: Vec3, direction: Vec3, out: MutableList<PlannedEmission>) {
        val basis = PlaneBasis(shape.angle.resolve(direction, random))
        val vertices = (0..shape.segments).map { i ->
            val along = basis.axis * (shape.length * i / shape.segments)
            val interior = i != 0 && i != shape.segments
            val side = if (interior && shape.jitter > 0.0) {
                basis.a * random.nextDouble(-shape.jitter, shape.jitter) + basis.b * random.nextDouble(-shape.jitter, shape.jitter)
            } else {
                Vec3.ZERO
            }
            origin + along + side
        }
        for (s in 0 until shape.segments) {
            for (k in 0 until shape.pointsPerSegment) {
                val point = vertices[s] + (vertices[s + 1] - vertices[s]) * (k.toDouble() / shape.pointsPerSegment)
                for (p in shape.particles) out += PlannedEmission(shape.delayTicks + p.delayTicks, point, p.toSpec(basis.axis))
            }
        }
        for (p in shape.particles) out += PlannedEmission(shape.delayTicks + p.delayTicks, vertices.last(), p.toSpec(basis.axis))
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
