package com.astarworks.astera.core.domain.effect.animation

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.geometry.Vec3
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/** The RTM math and timing (ADR-0196), checked against RTM's formulas, not against our own output. */
class ParticleAnimationSessionTest {

    private val orange = ParticleColor(255, 100, 20)
    private val origin = Vec3(10.0, 64.0, -5.0)

    /** RTM SimpleShoot `ProjectileEnd` as configured by the founder. */
    private val endRing = AnimatedShape.Circle(
        angle = ParticleAngle.RANDOM,
        amount = 2,
        periodTicks = 2,
        speed = 10,
        acceleration = 15,
        minRadius = 3.0,
        maxRadius = 4.0,
        repeatRadiusAcceleration = false,
        radiusAcceleration = 0.0,
        particles = listOf(AnimatedParticle("dust", color = orange, scale = 2.0f)),
    )

    @Test
    fun `projectile start is one flame burst of 50 particles at extra 0_2 on tick 0`() {
        val start = AnimatedShape.Point(particles = listOf(AnimatedParticle("flame", count = 50, extra = 0.2)))
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(start)), Random(1)).plan(origin, Vec3(0.0, 0.0, 1.0))

        assertThat(plan).hasSize(1)
        assertThat(plan.single().tickOffset).isZero()
        assertThat(plan.single().position).isEqualTo(origin)
        assertThat(plan.single().particle.key).isEqualTo("flame")
        assertThat(plan.single().particle.count).isEqualTo(50)
        assertThat(plan.single().particle.extra).isEqualTo(0.2)
    }

    @Test
    fun `end ring drops amount x period x acceleration dust points, 30 on each of ticks 0 and 1`() {
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(endRing)), Random(7)).plan(origin, Vec3(0.0, 0.0, 1.0))

        assertThat(plan).hasSize(2 * 2 * 15)
        assertThat(plan.groupBy { it.tickOffset }.mapValues { it.value.size }).containsExactlyInAnyOrderEntriesOf(mapOf(0 to 60 / 2, 1 to 60 / 2))
        assertThat(plan).allSatisfy {
            assertThat(it.particle.key).isEqualTo("dust")
            assertThat(it.particle.color).isEqualTo(orange)
            assertThat(it.particle.scale).isEqualTo(2.0f)
        }
    }

    @Test
    fun `every end ring point sits on a circle of radius 3 to 4 around the anchor`() {
        repeat(20) { seed ->
            val plan = ParticleAnimationSession(ParticleAnimation(listOf(endRing)), Random(seed)).plan(origin, Vec3(1.0, 0.0, 0.0))
            for (e in plan) {
                val r = e.position.distance(origin)
                assertThat(r).isBetween(3.0, 4.0)
            }
        }
    }

    @Test
    fun `an instance advances speed degrees per point and keeps its radius when radius acceleration is 0`() {
        val shape = AnimatedShape.Circle(
            amount = 1, periodTicks = 1, speed = 10, acceleration = 6,
            minRadius = 3.0, maxRadius = 4.0, repeatRadiusAcceleration = false,
            particles = listOf(AnimatedParticle("dust")),
        )
        val dir = Vec3(0.0, 0.0, 1.0)
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(3)).plan(Vec3.ZERO, dir)

        // RTM basis: u = direction, w = u x v, v = (0,-uz,uy) when |ux| <= |uy| -> v = (0,-1,0); w = (0,0,1)x(0,-1,0) = (1,0,0).
        val u = Vec3(0.0, 0.0, 1.0)
        val w = Vec3(1.0, 0.0, 0.0)
        val angles = plan.map { Math.toDegrees(kotlin.math.atan2(it.position.dot(w), it.position.dot(u))) }
        val steps = angles.zipWithNext { a, b -> ((b - a) % 360 + 540) % 360 - 180 }
        assertThat(steps).allSatisfy { assertThat(abs(it)).isCloseTo(10.0, Offset.offset(1e-6)) }
        // Same sign every step (the direction of rotation is fixed per instance).
        assertThat(steps.map { kotlin.math.sign(it) }.toSet()).hasSize(1)
        val radii = plan.map { it.position.length() }
        assertThat(radii.max() - radii.min()).isLessThan(1e-9)
    }

    @Test
    fun `non repeating radius acceleration wraps from max back to min like RTM`() {
        val shape = AnimatedShape.Circle(
            amount = 1, periodTicks = 1, speed = 0, acceleration = 1,
            minRadius = 1.0, maxRadius = 2.0, repeatRadiusAcceleration = false, radiusAcceleration = 0.4,
            particles = listOf(AnimatedParticle("dust")),
        )
        val cursor = CircleCursor(shape, Vec3(0.0, 0.0, 1.0), Random(5))
        var r = Random(5).let { rnd ->
            rnd.nextInt(360); rnd.nextBoolean(); rnd.nextDouble(1.0, 2.0)
        }
        repeat(12) {
            // RTM CircleParticleSetData.nextRadius, non-repeat branch.
            r += 0.4
            if (r >= 2.0) r = 1.0 + (r - 2.0)
            if (r <= 1.0) r = 2.0 - (1.0 - r)
            assertThat(cursor.nextRadius()).isCloseTo(r, Offset.offset(1e-12))
        }
    }

    @Test
    fun `repeating radius acceleration bounces inside the range`() {
        val shape = AnimatedShape.Circle(
            amount = 1, periodTicks = 1, speed = 0, acceleration = 1,
            minRadius = 1.0, maxRadius = 2.0, repeatRadiusAcceleration = true, radiusAcceleration = 0.3,
            particles = listOf(AnimatedParticle("dust")),
        )
        val cursor = CircleCursor(shape, Vec3(0.0, 0.0, 1.0), Random(11))
        val seen = (0 until 60).map { cursor.nextRadius() }
        assertThat(seen).allSatisfy { assertThat(it).isBetween(1.0, 2.0) }
        // It actually travels both ways (not stuck at a bound).
        assertThat(seen.max() - seen.min()).isGreaterThan(0.5)
    }

    @Test
    fun `timing is shape delay plus particle delay plus the period tick`() {
        val shape = AnimatedShape.Circle(
            delayTicks = 3, periodTicks = 2, acceleration = 1, minRadius = 1.0, maxRadius = 1.0,
            particles = listOf(AnimatedParticle("dust", delayTicks = 0), AnimatedParticle("flame", delayTicks = 5)),
        )
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(1)).plan(Vec3.ZERO, Vec3(0.0, 0.0, 1.0))

        assertThat(plan.map { it.tickOffset to it.particle.key })
            .containsExactly(3 to "dust", 4 to "dust", 8 to "flame", 9 to "flame")
    }

    @Test
    fun `point shape repeats amount times and honours both delays`() {
        val shape = AnimatedShape.Point(
            delayTicks = 2, amount = 3, particles = listOf(AnimatedParticle("flame", delayTicks = 4)),
        )
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(1)).plan(origin, Vec3(1.0, 0.0, 0.0))
        assertThat(plan.map { it.tickOffset }).containsExactly(6, 6, 6)
    }

    @Test
    fun `a session keeps orbiting across plan calls while a fresh session restarts`() {
        val shape = AnimatedShape.Circle(
            periodTicks = 1, speed = 20, acceleration = 1, minRadius = 2.0, maxRadius = 2.0,
            particles = listOf(AnimatedParticle("dust")),
        )
        val a = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(9))
        val first = a.plan(Vec3.ZERO, Vec3(0.0, 0.0, 1.0)).single().position
        val second = a.plan(Vec3.ZERO, Vec3(0.0, 0.0, 1.0)).single().position
        assertThat(second).isNotEqualTo(first)

        val b = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(9))
        assertThat(b.plan(Vec3.ZERO, Vec3(0.0, 0.0, 1.0)).single().position).isEqualTo(first)
    }

    @Test
    fun `count 0 particles carry the outward direction as their velocity`() {
        val shape = AnimatedShape.Circle(
            periodTicks = 1, speed = 0, acceleration = 1, minRadius = 2.0, maxRadius = 2.0,
            particles = listOf(AnimatedParticle("flame", count = 0, extra = 0.5)),
        )
        val e = ParticleAnimationSession(ParticleAnimation(listOf(shape)), Random(2)).plan(Vec3.ZERO, Vec3(0.0, 0.0, 1.0)).single()
        assertThat(e.particle.offset.length()).isCloseTo(1.0, Offset.offset(1e-9))
        assertThat(e.particle.offset).isEqualTo(e.position.normalize())
    }

    @Test
    fun `angle modes resolve like RTM`() {
        val rnd = Random(4)
        val dir = Vec3(3.0, 4.0, 0.0)
        assertThat(ParticleAngle.RAW.resolve(dir, rnd)).isEqualTo(dir)
        assertThat(ParticleAngle.VERTICAL.resolve(dir, rnd)).isEqualTo(Vec3(0.0, 1.0, 0.0))
        assertThat(ParticleAngle.HORIZONTAL.resolve(dir, rnd)).isEqualTo(Vec3(1.0, 0.0, 0.0))
        val flat = ParticleAngle.HORIZONTAL.resolve(Vec3(1.0, 0.0, 0.0), rnd)
        assertThat(flat.y).isZero()
        assertThat(flat.length()).isCloseTo(1.0, Offset.offset(1e-9))
        repeat(50) {
            assertThat(ParticleAngle.RANDOM.resolve(dir, rnd).length()).isCloseTo(1.0, Offset.offset(1e-9))
        }
    }

    @Test
    fun `arc lays points on a horizontal arc of the radius, sweeping right to left over the sweep ticks`() {
        val arc = AnimatedShape.Arc(
            particles = listOf(AnimatedParticle("sweep_attack")),
            radius = 3.0,
            startDegrees = 60.0,
            endDegrees = -60.0,
            points = 5,
            sweepTicks = 4,
            heightStart = 1.0,
            heightEnd = 0.0,
        )
        // facing +z: right is -x
        val plan = ParticleAnimationSession(ParticleAnimation(listOf(arc)), Random(1)).plan(origin, Vec3(0.0, 0.3, 1.0))

        assertThat(plan.map { it.tickOffset }).containsExactly(0, 1, 2, 3, 4)
        plan.forEach {
            val d = it.position - origin
            assertThat(kotlin.math.hypot(d.x, d.z)).isCloseTo(3.0, Offset.offset(1e-9))
        }
        val first = plan.first().position - origin
        assertThat(first.x).isCloseTo(-3.0 * kotlin.math.sin(Math.toRadians(60.0)), Offset.offset(1e-9)) // on the right (-x)
        assertThat(first.y).isCloseTo(1.0, Offset.offset(1e-9))
        val mid = plan[2].position - origin
        assertThat(mid.z).isCloseTo(3.0, Offset.offset(1e-9))
        assertThat(mid.y).isCloseTo(0.5, Offset.offset(1e-9))
        assertThat((plan.last().position - origin).x).isGreaterThan(0.0) // ends on the left (+x)
    }
}
