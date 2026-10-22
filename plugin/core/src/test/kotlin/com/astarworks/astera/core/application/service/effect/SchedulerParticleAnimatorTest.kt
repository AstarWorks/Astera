package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.effect.ParticleColor
import com.astarworks.astera.core.domain.effect.animation.AnimatedParticle
import com.astarworks.astera.core.domain.effect.animation.AnimatedShape
import com.astarworks.astera.core.domain.effect.animation.ParticleAnimation
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class SchedulerParticleAnimatorTest {

    private val world = WorldId("arena")
    private val scheduler = FakeScheduler(startTick = 0L)
    private val gateway = FakeWorldGateway()
    private val animator = SchedulerParticleAnimator(scheduler, gateway) { Random(1) }

    private val ring = AnimatedShape.Circle(
        angle = com.astarworks.astera.core.domain.effect.animation.ParticleAngle.RANDOM,
        amount = 2, periodTicks = 2, speed = 10, acceleration = 15, minRadius = 3.0, maxRadius = 4.0,
        repeatRadiusAcceleration = false,
        particles = listOf(AnimatedParticle("dust", color = ParticleColor(255, 100, 20), scale = 2.0f)),
    )

    @Test
    fun `tick 0 spawns happen synchronously, the rest on their tick`() {
        val burst = AnimatedShape.Point(particles = listOf(AnimatedParticle("flame", count = 50, extra = 0.2)))
        animator.play(world, Vec3(1.0, 2.0, 3.0), Vec3(0.0, 0.0, 1.0), ParticleAnimation(listOf(burst, ring)))

        // Burst (1) + first ring tick (2 instances x 15) land before play() returns.
        assertThat(gateway.particles).hasSize(1 + 30)
        scheduler.advanceTicks(1)
        assertThat(gateway.particles).hasSize(1 + 30 + 30)
        scheduler.advanceTicks(5)
        assertThat(gateway.particles).hasSize(61)
    }

    @Test
    fun `dust arrives with colour and size on the spawn spec`() {
        animator.play(world, Vec3.ZERO, Vec3(0.0, 0.0, 1.0), ParticleAnimation(listOf(ring)))
        val spec = gateway.particles.first().particle
        assertThat(spec.key).isEqualTo("dust")
        assertThat(spec.color).isEqualTo(ParticleColor(255, 100, 20))
        assertThat(spec.scale).isEqualTo(2.0f)
    }

    @Test
    fun `cancelling a player drops the not yet fired ticks`() {
        val player = animator.open(world, ParticleAnimation(listOf(ring)))
        player.play(Vec3.ZERO, Vec3(0.0, 0.0, 1.0))
        assertThat(gateway.particles).hasSize(30)
        player.cancel()
        scheduler.advanceTicks(5)
        assertThat(gateway.particles).hasSize(30)
    }

    @Test
    fun `the returned handle cancels one play only`() {
        val player = animator.open(world, ParticleAnimation(listOf(ring)))
        val first = player.play(Vec3.ZERO, Vec3(0.0, 0.0, 1.0))
        first.cancel()
        scheduler.advanceTicks(5)
        assertThat(gateway.particles).hasSize(30)
    }
}
