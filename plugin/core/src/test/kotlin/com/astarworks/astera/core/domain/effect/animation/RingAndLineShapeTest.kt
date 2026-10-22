package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.hypot
import kotlin.random.Random

/** Astera-only shapes: the expanding ring and the jagged line. */
class RingAndLineShapeTest {

    private val origin = Vec3(10.0, 64.0, -5.0)
    private val spark = listOf(AnimatedParticle("electric_spark"))
    private val eps = Offset.offset(1e-9)

    private fun plan(shape: AnimatedShape, dir: Vec3 = Vec3(0.0, 0.0, 1.0), seed: Int = 3) =
        ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(seed)).plan(origin, dir)

    @Test
    fun `ring emits points per tick and the radius grows from start to end`() {
        val plan = plan(AnimatedShape.Ring(particles = spark, periodTicks = 5, points = 12, startRadius = 1.0, endRadius = 5.0))

        assertThat(plan).hasSize(5 * 12)
        val byTick = plan.groupBy { it.tickOffset }
        assertThat(byTick.keys).containsExactly(0, 1, 2, 3, 4)
        byTick.forEach { (tick, emissions) ->
            assertThat(emissions).hasSize(12)
            emissions.forEach {
                // flat ring: same height as the anchor, on the circle of the tick's radius
                assertThat(it.position.y).isCloseTo(origin.y, eps)
                assertThat(it.position.distance(origin)).isCloseTo(1.0 + tick, eps)
            }
        }
    }

    @Test
    fun `ring points are evenly spaced and directional particles fly outward`() {
        val shape = AnimatedShape.Ring(particles = listOf(AnimatedParticle("flame", count = 0, extra = 0.1)), points = 4, endRadius = 2.0)
        val plan = plan(shape)

        assertThat(plan).hasSize(4)
        val first = plan[0].position - origin
        plan.forEach { assertThat(it.position.distance(origin)).isCloseTo(2.0, eps) }
        assertThat((plan[2].position - origin).dot(first)).isCloseTo(-4.0, eps) // 180 degrees apart
        plan.forEach { assertThat(it.particle.offset.dot(it.position - origin)).isCloseTo(2.0, eps) } // outward unit vector
    }

    @Test
    fun `ring delay shifts every tick`() {
        val plan = plan(AnimatedShape.Ring(particles = spark, delayTicks = 3, periodTicks = 2, points = 2))
        assertThat(plan.map { it.tickOffset }.toSet()).containsExactlyInAnyOrder(3, 4)
    }

    @Test
    fun `line spans the length along the axis with fixed endpoints and jittered interior`() {
        val shape = AnimatedShape.Line(particles = spark, angle = ParticleAngle.DOWN, length = 9.0, segments = 6, jitter = 0.5, pointsPerSegment = 4)
        val plan = plan(shape)

        assertThat(plan).hasSize(6 * 4 + 1)
        assertThat(plan.map { it.tickOffset }.toSet()).containsExactly(0)
        assertThat(plan.first().position).isEqualTo(origin)
        assertThat(plan.last().position.x).isCloseTo(origin.x, eps)
        assertThat(plan.last().position.y).isCloseTo(origin.y - 9.0, eps)
        assertThat(plan.last().position.z).isCloseTo(origin.z, eps)
        val lateral = plan.maxOf { hypot(it.position.x - origin.x, it.position.z - origin.z) }
        assertThat(lateral).isGreaterThan(0.05).isLessThanOrEqualTo(0.5 * 1.4143)
    }

    @Test
    fun `line without jitter is perfectly straight`() {
        val plan = plan(AnimatedShape.Line(particles = spark, length = 4.0, segments = 4, jitter = 0.0, pointsPerSegment = 1), Vec3(1.0, 0.0, 0.0))
        plan.forEach {
            assertThat(it.position.y).isCloseTo(origin.y, eps)
            assertThat(it.position.z).isCloseTo(origin.z, eps)
        }
        assertThat(plan.last().position.x).isCloseTo(origin.x + 4.0, eps)
    }

    @Test
    fun `invalid parameters are rejected`() {
        assertThrows<IllegalArgumentException> { AnimatedShape.Ring(particles = spark, periodTicks = 0) }
        assertThrows<IllegalArgumentException> { AnimatedShape.Line(particles = spark, length = 0.0) }
        assertThrows<IllegalArgumentException> { AnimatedShape.Line(particles = spark, segments = 0) }
    }
}
