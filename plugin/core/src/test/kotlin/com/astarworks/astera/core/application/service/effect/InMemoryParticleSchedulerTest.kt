package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Tick-driven behaviour tests for [InMemoryParticleScheduler]
 * (Wave 5effects / ADR-0109).
 *
 * Uses the deterministic [FakeScheduler] + [FakeWorldGateway] from
 * test-fixtures — the same substrate Wave 5m AreaEffect tests rely on.
 */
class InMemoryParticleSchedulerTest {

    private val world = WorldId("arena")
    private val particle = ParticleSpec(key = "minecraft:flame", count = 4)

    @Test
    fun `EmitOnce fires at the requested absolute tick`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        val handle = sut.schedule(
            ScheduledParticleSpec.EmitOnce(
                particle = particle,
                position = Vec3(0.0, 64.0, 0.0),
                atTick = 5L,
                world = world,
            ),
        )

        assertThat(handle.isActive).isTrue()
        scheduler.advanceTicks(4)
        assertThat(worldGateway.particles).isEmpty()
        scheduler.advanceTicks(1)
        assertThat(worldGateway.particles).hasSize(1)
        assertThat(worldGateway.particles.single().at).isEqualTo(Vec3(0.0, 64.0, 0.0))
    }

    @Test
    fun `Loop fires every interval until duration elapses`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        sut.schedule(
            ScheduledParticleSpec.Loop(
                particle = particle,
                position = Vec3.ZERO,
                intervalTicks = 4L,
                durationTicks = 16L,
                world = world,
            ),
        )

        scheduler.advanceTicks(20)
        // Fires at t=0,4,8,12,16 = 5 emissions.
        assertThat(worldGateway.particles.size).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `Loop cancel stops future emissions`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        val handle = sut.schedule(
            ScheduledParticleSpec.Loop(
                particle = particle,
                position = Vec3.ZERO,
                intervalTicks = 2L,
                durationTicks = 100L,
                world = world,
            ),
        )

        scheduler.advanceTicks(6)
        val emittedBeforeCancel = worldGateway.particles.size
        handle.cancel()
        assertThat(handle.isActive).isFalse()
        scheduler.advanceTicks(20)
        assertThat(worldGateway.particles.size).isEqualTo(emittedBeforeCancel)
    }

    @Test
    fun `Burst spreads emissions across spreadTicks`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        sut.schedule(
            ScheduledParticleSpec.Burst(
                particle = particle,
                position = Vec3.ZERO,
                count = 4,
                spreadTicks = 8L,
                world = world,
            ),
        )

        scheduler.advanceTicks(0)
        val attTick0 = worldGateway.particles.size
        scheduler.advanceTicks(4)
        val attTick4 = worldGateway.particles.size
        scheduler.advanceTicks(8)
        val attTick12 = worldGateway.particles.size

        assertThat(attTick0).isLessThanOrEqualTo(attTick4)
        assertThat(attTick4).isLessThanOrEqualTo(attTick12)
        assertThat(attTick12).isEqualTo(4)
    }

    @Test
    fun `RingExpansion fires at each step over duration`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        sut.schedule(
            ScheduledParticleSpec.RingExpansion(
                particle = particle,
                center = Vec3.ZERO,
                expandRate = 1.0,
                durationTicks = 4L,
                stepTicks = 1L,
                world = world,
            ),
        )

        scheduler.advanceTicks(5)
        // Each step emits a full ring of samples (>= 8 per ring).
        assertThat(worldGateway.particles.size).isGreaterThanOrEqualTo(8)
    }

    @Test
    fun `schedule drops with null world (Phase 1 default-world deferred)`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryParticleScheduler(scheduler, worldGateway)

        val handle = sut.schedule(
            ScheduledParticleSpec.EmitOnce(
                particle = particle,
                position = Vec3.ZERO,
                atTick = 1L,
                world = null,
            ),
        )

        assertThat(handle.isActive).isFalse()
        scheduler.advanceTicks(5)
        assertThat(worldGateway.particles).isEmpty()
    }
}
