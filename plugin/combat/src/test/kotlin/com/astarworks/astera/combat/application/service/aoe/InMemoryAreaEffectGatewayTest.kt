package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.domain.aoe.AreaEffectAction
import com.astarworks.astera.combat.domain.aoe.AreaEffectError
import com.astarworks.astera.combat.domain.aoe.AreaEffectHandle
import com.astarworks.astera.combat.domain.aoe.AreaEffectShape
import com.astarworks.astera.combat.domain.aoe.AreaEffectSpec
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectDespawned
import com.astarworks.astera.combat.domain.event.aoe.AreaEffectSpawned
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.damage.DamageProfile
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.status.StatusEffectId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryAreaEffectGatewayTest {

    private val world = WorldId("test")
    private val center = Vec3(0.0, 64.0, 0.0)

    private fun spec(
        shape: AreaEffectShape = AreaEffectShape.Circle(3.0),
        duration: Int = 40,
        interval: Int = 10,
        action: AreaEffectAction = AreaEffectAction.SpawnParticle(
            com.astarworks.astera.core.domain.effect.ParticleSpec("smoke", 5),
        ),
    ): AreaEffectSpec = AreaEffectSpec(
        center = center,
        world = world,
        shape = shape,
        durationTicks = duration,
        tickIntervalTicks = interval,
        effectPerTick = action,
    )

    @Test
    fun `spawn publishes AreaEffectSpawned with backend id`() {
        val bus = FakeDomainEventBus()
        val gateway = InMemoryAreaEffectGateway(FakeScheduler(), bus)

        val handle = (gateway.spawn(spec()) as Result.Success).value
        assertThat(handle.backendId).isEqualTo(InMemoryAreaEffectGateway.BACKEND_ID)
        val event = bus.published.single() as AreaEffectSpawned
        assertThat(event.handle).isEqualTo(handle)
        assertThat(event.center).isEqualTo(center)
    }

    @Test
    fun `despawn cancels timers and publishes AreaEffectDespawned`() {
        val bus = FakeDomainEventBus()
        val gateway = InMemoryAreaEffectGateway(FakeScheduler(), bus)
        val handle = (gateway.spawn(spec()) as Result.Success).value
        bus.published.clear()

        val outcome = gateway.despawn(handle)
        assertThat(outcome.isSuccess).isTrue
        val event = bus.published.single() as AreaEffectDespawned
        assertThat(event.handle).isEqualTo(handle)
        assertThat(event.reason).isEqualTo(AreaEffectDespawned.DespawnReason.ExplicitDespawn)
        assertThat(gateway.all()).isEmpty()
    }

    @Test
    fun `despawn returns UnknownHandle for foreign backend id`() {
        val bus = FakeDomainEventBus()
        val gateway = InMemoryAreaEffectGateway(FakeScheduler(), bus)

        val foreign = AreaEffectHandle(UUID.randomUUID(), backendId = "different")
        val outcome = gateway.despawn(foreign)
        assertThat(outcome.errorOrNull()).isInstanceOf(AreaEffectError.UnknownHandle::class.java)
    }

    @Test
    fun `spawn duration zero triggers immediate tick and despawn`() {
        val bus = FakeDomainEventBus()
        var ticks = 0
        val gateway = InMemoryAreaEffectGateway(
            scheduler = FakeScheduler(),
            eventBus = bus,
            tickAction = { _, _ -> ticks++ },
        )

        val handle = (gateway.spawn(spec(duration = 0)) as Result.Success).value
        assertThat(ticks).isEqualTo(1)
        assertThat(gateway.all()).isEmpty()
        val despawn = bus.published.filterIsInstance<AreaEffectDespawned>().single()
        assertThat(despawn.handle).isEqualTo(handle)
        assertThat(despawn.reason).isEqualTo(AreaEffectDespawned.DespawnReason.ExpiredNaturally)
    }

    @Test
    fun `Circle Ring Box validate dimensions`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            AreaEffectShape.Circle(-1.0)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            AreaEffectShape.Ring(5.0, 5.0)
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            AreaEffectShape.Box(-1.0, 2.0, 3.0)
        }
    }

    @Test
    fun `AreaEffectAction variants compile`() {
        val a: AreaEffectAction = AreaEffectAction.ApplyStatusEffect(StatusEffectId("astera-test"))
        val b: AreaEffectAction = AreaEffectAction.DealDamage(
            DamageProfile.single(2.0, DamageAttribute.FIRE),
        )
        val c: AreaEffectAction = AreaEffectAction.SpawnParticle(
            com.astarworks.astera.core.domain.effect.ParticleSpec("flame", 3),
        )
        assertThat(listOf(a, b, c)).hasSize(3)
    }

    @Test
    fun `tick interval below 1 is rejected at spec construction`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            spec(interval = 0)
        }
    }
}
