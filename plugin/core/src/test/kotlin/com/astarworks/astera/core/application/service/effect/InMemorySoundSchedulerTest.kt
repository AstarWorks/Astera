package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.domain.effect.SoundSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.effect.sound.TimedSound
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemorySoundSchedulerTest {

    private val world = WorldId("arena")
    private val sound = SoundSpec(key = "astera:skill/fireball/cast")

    @Test
    fun `Delayed fires after delayTicks`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemorySoundScheduler(scheduler, worldGateway)

        sut.schedule(
            ScheduledSoundSpec.Delayed(
                sound = sound,
                position = Vec3.ZERO,
                delayTicks = 10L,
                world = world,
            ),
        )

        scheduler.advanceTicks(9)
        assertThat(worldGateway.sounds).isEmpty()
        scheduler.advanceTicks(1)
        assertThat(worldGateway.sounds).hasSize(1)
    }

    @Test
    fun `Sequence fires every step at its relative offset`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemorySoundScheduler(scheduler, worldGateway)

        sut.schedule(
            ScheduledSoundSpec.Sequence(
                steps = listOf(
                    TimedSound(sound = sound, position = Vec3.ZERO, atTick = 0L),
                    TimedSound(sound = sound, position = Vec3.ZERO, atTick = 5L),
                    TimedSound(sound = sound, position = Vec3.ZERO, atTick = 10L),
                ),
                world = world,
            ),
        )

        scheduler.advanceTicks(11)
        assertThat(worldGateway.sounds).hasSize(3)
    }

    @Test
    fun `Loop cancel stops future plays`() {
        val scheduler = FakeScheduler(startTick = 0L)
        val worldGateway = FakeWorldGateway()
        val sut = InMemorySoundScheduler(scheduler, worldGateway)

        val handle = sut.schedule(
            ScheduledSoundSpec.Loop(
                sound = sound,
                position = Vec3.ZERO,
                intervalTicks = 2L,
                durationTicks = 100L,
                world = world,
            ),
        )

        scheduler.advanceTicks(6)
        val before = worldGateway.sounds.size
        handle.cancel()
        scheduler.advanceTicks(20)
        assertThat(worldGateway.sounds.size).isEqualTo(before)
    }
}
