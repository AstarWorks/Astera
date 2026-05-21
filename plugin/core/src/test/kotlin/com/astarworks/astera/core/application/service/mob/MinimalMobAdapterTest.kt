package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobSkillTriggered
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.mob.MobAiHint
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobSkillBinding
import com.astarworks.astera.core.domain.mob.MobSpec
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.skill.SkillId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class MinimalMobAdapterTest {

    private val world = WorldId("test")
    private val origin = Vec3(0.0, 64.0, 0.0)

    private fun adapter(): Triple<MinimalMobAdapter, InMemoryDomainEventBus, FakeScheduler> {
        val bus = InMemoryDomainEventBus()
        val scheduler = FakeScheduler(startTick = 100L)
        return Triple(MinimalMobAdapter(bus, scheduler), bus, scheduler)
    }

    private fun spec(id: String = "astera:test-wolf"): MobSpec = MobSpec(
        id = MobTypeId(id),
        displayName = LocalizedText.EMPTY,
        baseEntityType = EntityCategory.MOB,
        attributes = SimpleAttributeContainer(),
    )

    @Test
    fun `backendId is the canonical MINIMAL constant`() {
        val (a, _, _) = adapter()
        assertThat(a.backendId()).isEqualTo(MobBackendId.MINIMAL)
        assertThat(a.backendId().value).isEqualTo("minimal")
    }

    @Test
    fun `isAvailable always returns true`() {
        val (a, _, _) = adapter()
        assertThat(a.isAvailable()).isTrue()
    }

    @Test
    fun `spawn returns a handle tagged with minimal backend`() {
        val (a, _, _) = adapter()
        val result = a.spawn(world, origin, spec())
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val handle = (result as Result.Success).value
        assertThat(handle.backendId).isEqualTo(MobBackendId.MINIMAL.value)
        assertThat(a.trackedHandles()).contains(handle)
        assertThat(a.mobTypeOf(handle)?.value).isEqualTo("astera:test-wolf")
    }

    @Test
    fun `despawn removes tracked state`() {
        val (a, _, _) = adapter()
        val handle = (a.spawn(world, origin, spec()) as Result.Success).value
        assertThat(a.trackedHandles()).hasSize(1)
        a.despawn(handle)
        assertThat(a.trackedHandles()).isEmpty()
    }

    @Test
    fun `despawn unknown handle returns BackendError`() {
        val (a, _, _) = adapter()
        val ghost = EntityHandle(id = UUID.randomUUID(), backendId = MobBackendId.MINIMAL.value)
        val result = a.despawn(ghost)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `applySkill publishes MobSkillTriggered for tracked handle`() {
        val (a, bus, scheduler) = adapter()
        val handle = (a.spawn(world, origin, spec()) as Result.Success).value

        val captured = mutableListOf<MobSkillTriggered>()
        bus.subscribe(MobSkillTriggered::class) { captured += it }

        val binding = MobSkillBinding.OnPlayerNear(
            skillId = SkillId("wolf-bite"),
            range = 5.0,
            cooldownTicks = 40,
        )
        val result = a.applySkill(handle, binding)
        assertThat(result).isInstanceOf(Result.Success::class.java)

        assertThat(captured).hasSize(1)
        with(captured.single()) {
            assertThat(handle).isEqualTo(handle)
            assertThat(skillId).isEqualTo(SkillId("wolf-bite"))
            assertThat(trigger).isEqualTo(binding)
            assertThat(atTick).isEqualTo(scheduler.currentTick())
            assertThat(mobTypeId).isEqualTo(MobTypeId("astera:test-wolf"))
        }
    }

    @Test
    fun `applySkill on unknown handle returns BackendError without publishing`() {
        val (a, bus, _) = adapter()
        val captured = mutableListOf<MobSkillTriggered>()
        bus.subscribe(MobSkillTriggered::class) { captured += it }

        val ghost = EntityHandle(id = UUID.randomUUID(), backendId = MobBackendId.MINIMAL.value)
        val result = a.applySkill(ghost, MobSkillBinding.OnDeath(SkillId("rattle")))
        assertThat(result.isFailure).isTrue()
        assertThat(captured).isEmpty()
    }

    @Test
    fun `supportsAi honours Default and Stationary only`() {
        val (a, _, _) = adapter()
        assertThat(a.supportsAi(MobAiHint.Default)).isTrue()
        assertThat(a.supportsAi(MobAiHint.Stationary)).isTrue()
        assertThat(a.supportsAi(MobAiHint.Pathfind)).isFalse()
        assertThat(a.supportsAi(MobAiHint.Flee)).isFalse()
        assertThat(a.supportsAi(MobAiHint.Aggressive)).isFalse()
    }

    @Test
    fun `clear drops all tracked state`() {
        val (a, _, _) = adapter()
        a.spawn(world, origin, spec())
        a.spawn(world, origin, spec("astera:other-wolf"))
        assertThat(a.trackedHandles()).hasSize(2)
        a.clear()
        assertThat(a.trackedHandles()).isEmpty()
    }
}
