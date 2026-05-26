package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.ParticleSpec
import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.effect.chain.EffectChain
import com.astarworks.astera.core.domain.effect.chain.EffectStep
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryEffectOrchestratorTest {

    private val world = WorldId("arena")
    private val target = PlayerId(UUID.randomUUID())
    private val particle = ParticleSpec(key = "minecraft:flame", count = 4)
    private val sound = SoundSpec(key = "astera:boss/phase/transition")

    private class RecordingStatusApplier :
        com.astarworks.astera.core.application.port.outbound.effect.IStatusEffectApplier {
        val calls = mutableListOf<Triple<PlayerId, StatusEffectId, Int>>()
        override fun apply(target: PlayerId, effectId: StatusEffectId, durationTicks: Int) {
            calls += Triple(target, effectId, durationTicks)
        }
    }

    private class RecordingDamageApplier :
        com.astarworks.astera.core.application.port.outbound.effect.IDamageApplier {
        val calls = mutableListOf<Pair<PlayerId, DamageProfile>>()
        override fun apply(target: PlayerId, profile: DamageProfile) {
            calls += target to profile
        }
    }

    @Test
    fun `Sequence walks steps in order respecting DelayStep`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val status = RecordingStatusApplier()
        val damage = RecordingDamageApplier()
        val sut = InMemoryEffectOrchestrator(scheduler, worldGateway, status, damage)

        sut.play(
            EffectChain.Sequence(
                listOf(
                    EffectStep.ParticleStep(particle, Vec3.ZERO, world),
                    EffectStep.DelayStep(10L),
                    EffectStep.SoundStep(sound, Vec3.ZERO, world),
                    EffectStep.DelayStep(5L),
                    EffectStep.StatusEffectStep(StatusEffectId("burn"), target, 40),
                    EffectStep.DamageStep(DamageProfile.single(8.0, DamageAttribute.PHYSICAL), target),
                ),
            ),
        )

        scheduler.advanceTicks(0)
        assertThat(worldGateway.particles).hasSize(1)
        assertThat(worldGateway.sounds).isEmpty()

        scheduler.advanceTicks(10)
        assertThat(worldGateway.sounds).hasSize(1)
        assertThat(status.calls).isEmpty()

        scheduler.advanceTicks(5)
        assertThat(status.calls).hasSize(1)
        assertThat(damage.calls).hasSize(1)
    }

    @Test
    fun `Parallel branches fire concurrently`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val status = RecordingStatusApplier()
        val damage = RecordingDamageApplier()
        val sut = InMemoryEffectOrchestrator(scheduler, worldGateway, status, damage)

        sut.play(
            EffectChain.Parallel(
                listOf(
                    EffectChain.EmitOnce(EffectStep.ParticleStep(particle, Vec3.ZERO, world)),
                    EffectChain.EmitOnce(EffectStep.SoundStep(sound, Vec3.ZERO, world)),
                    EffectChain.EmitOnce(
                        EffectStep.DamageStep(DamageProfile.single(5.0, DamageAttribute.FIRE), target),
                    ),
                ),
            ),
        )

        scheduler.advanceTicks(1)
        assertThat(worldGateway.particles).hasSize(1)
        assertThat(worldGateway.sounds).hasSize(1)
        assertThat(damage.calls).hasSize(1)
    }

    @Test
    fun `cancel tears down every pending step`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val status = RecordingStatusApplier()
        val damage = RecordingDamageApplier()
        val sut = InMemoryEffectOrchestrator(scheduler, worldGateway, status, damage)

        val handle = sut.play(
            EffectChain.Sequence(
                listOf(
                    EffectStep.DelayStep(10L),
                    EffectStep.ParticleStep(particle, Vec3.ZERO, world),
                ),
            ),
        )

        scheduler.advanceTicks(5)
        handle.cancel()
        scheduler.advanceTicks(20)
        assertThat(worldGateway.particles).isEmpty()
    }

    @Test
    fun `Loop with durationTicks 0 is dropped (infinite guard)`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemoryEffectOrchestrator(
            scheduler, worldGateway, RecordingStatusApplier(), RecordingDamageApplier(),
        )

        val handle = sut.play(
            EffectChain.Loop(
                chain = EffectChain.EmitOnce(EffectStep.ParticleStep(particle, Vec3.ZERO, world)),
                durationTicks = 0L,
                intervalTicks = 1L,
            ),
        )

        scheduler.advanceTicks(100)
        assertThat(worldGateway.particles).isEmpty()
        // handle still active (empty composite) — cancel is a no-op
        handle.cancel()
    }
}
