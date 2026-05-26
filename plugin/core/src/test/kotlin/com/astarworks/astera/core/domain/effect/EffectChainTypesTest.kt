package com.astarworks.astera.core.domain.effect

import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.effect.chain.EffectChain
import com.astarworks.astera.core.domain.effect.chain.EffectStep
import com.astarworks.astera.core.domain.effect.ratelimit.RateLimitedAction
import com.astarworks.astera.core.domain.effect.scheduler.ScheduledParticleSpec
import com.astarworks.astera.core.domain.effect.sound.ScheduledSoundSpec
import com.astarworks.astera.core.domain.effect.sound.SoundDefinition
import com.astarworks.astera.core.domain.effect.sound.TimedSound
import com.astarworks.astera.core.domain.effect.timer.CountdownTimerId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

@Suppress("UnusedPrivateProperty")

/**
 * Validation + construction tests for Wave 5effects (ADR-0109) domain
 * value types. Each sealed variant gets at least one happy-path
 * construction case and one rejection case for its invariants.
 */
class EffectChainTypesTest {

    private val world = WorldId("arena")
    private val player = PlayerId(UUID.randomUUID())
    private val particle = ParticleSpec(key = "minecraft:flame", count = 4)
    private val sound = SoundSpec(key = "astera:skill/cast", volume = 1.0, pitch = 1.0)

    // ----- ScheduledParticleSpec --------------------------------------

    @Test
    fun `EmitOnce constructs with absolute tick`() {
        val spec = ScheduledParticleSpec.EmitOnce(
            particle = particle,
            position = Vec3(0.0, 64.0, 0.0),
            atTick = 100L,
            world = world,
        )
        assertThat(spec.atTick).isEqualTo(100L)
    }

    @Test
    fun `Loop rejects intervalTicks lt 1`() {
        assertThatThrownBy {
            ScheduledParticleSpec.Loop(
                particle = particle,
                position = Vec3.ZERO,
                intervalTicks = 0L,
                durationTicks = 40L,
                world = world,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Loop with durationTicks 0 means caller-controlled lifetime`() {
        val spec = ScheduledParticleSpec.Loop(
            particle = particle,
            position = Vec3.ZERO,
            intervalTicks = 1L,
            durationTicks = 0L,
            world = world,
        )
        assertThat(spec.durationTicks).isZero()
    }

    @Test
    fun `Burst rejects count lt 1`() {
        assertThatThrownBy {
            ScheduledParticleSpec.Burst(
                particle = particle,
                position = Vec3.ZERO,
                count = 0,
                spreadTicks = 4L,
                world = world,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Pulse rejects negative fromRadius`() {
        assertThatThrownBy {
            ScheduledParticleSpec.Pulse(
                particle = particle,
                center = Vec3.ZERO,
                fromRadius = -1.0,
                toRadius = 5.0,
                durationTicks = 40L,
                world = world,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RingExpansion rejects expandRate 0`() {
        assertThatThrownBy {
            ScheduledParticleSpec.RingExpansion(
                particle = particle,
                center = Vec3.ZERO,
                expandRate = 0.0,
                durationTicks = 20L,
                world = world,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- ScheduledSoundSpec -----------------------------------------

    @Test
    fun `Sound Sequence rejects empty steps`() {
        assertThatThrownBy {
            ScheduledSoundSpec.Sequence(steps = emptyList(), world = world)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Sound Loop accepts durationTicks 0 (caller controlled)`() {
        val spec = ScheduledSoundSpec.Loop(
            sound = sound,
            position = Vec3.ZERO,
            intervalTicks = 20L,
            durationTicks = 0L,
            world = world,
        )
        assertThat(spec.durationTicks).isZero()
    }

    @Test
    fun `TimedSound rejects negative atTick`() {
        assertThatThrownBy {
            TimedSound(sound = sound, position = Vec3.ZERO, atTick = -1L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- EffectChain ------------------------------------------------

    @Test
    fun `EffectChain Sequence rejects empty steps`() {
        assertThatThrownBy {
            EffectChain.Sequence(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EffectChain Parallel rejects empty branches`() {
        assertThatThrownBy {
            EffectChain.Parallel(emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EffectChain Loop intervalTicks must be at least 1`() {
        val inner = EffectChain.EmitOnce(EffectStep.DelayStep(1L))
        assertThatThrownBy {
            EffectChain.Loop(inner, durationTicks = 20L, intervalTicks = 0L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EffectStep DamageStep accepts null target (orchestrator handles)`() {
        val step = EffectStep.DamageStep(
            profile = DamageProfile.single(8.0, DamageAttribute.PHYSICAL),
            target = null,
        )
        assertThat(step.target).isNull()
    }

    @Test
    fun `EffectStep StatusEffectStep rejects negative duration`() {
        assertThatThrownBy {
            EffectStep.StatusEffectStep(
                effectId = StatusEffectId("burn"),
                target = player,
                durationTicks = -1,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `EffectStep DelayStep rejects negative duration`() {
        assertThatThrownBy {
            EffectStep.DelayStep(-1L)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- Identifiers ------------------------------------------------

    @Test
    fun `CountdownTimerId rejects uppercase`() {
        assertThatThrownBy { CountdownTimerId("Match-Timer") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RateLimitedAction rejects underscore`() {
        assertThatThrownBy { RateLimitedAction("skill_cast") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RateLimitedAction accepts kebab`() {
        val a = RateLimitedAction("skill-cast")
        assertThat(a.value).isEqualTo("skill-cast")
    }

    // ----- SoundDefinition --------------------------------------------

    @Test
    fun `SoundDefinition rejects blank resourceKey`() {
        assertThatThrownBy {
            SoundDefinition(
                id = com.astarworks.astera.core.domain.effect.SoundId("astera:skill/cast"),
                resourceKey = "",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `SoundDefinition clamps volume range`() {
        assertThatThrownBy {
            SoundDefinition(
                id = com.astarworks.astera.core.domain.effect.SoundId("astera:skill/cast"),
                resourceKey = "astera:skill/cast",
                defaultVolume = 10.0,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
