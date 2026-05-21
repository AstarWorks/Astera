package com.astarworks.astera.combat.application.service.aoe

import com.astarworks.astera.combat.domain.event.aoe.LightningStrike
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryLightningGatewayTest {

    private val world = WorldId("test")
    private val position = Vec3(50.0, 70.0, -25.0)

    @Test
    fun `strike publishes LightningStrike with damage flag`() {
        val bus = FakeDomainEventBus()
        val scheduler = FakeScheduler(startTick = 99L)
        val gateway = InMemoryLightningGateway(bus, scheduler)

        val outcome = gateway.strike(world, position, damageEntities = true)
        assertThat(outcome.isSuccess).isTrue
        val event = bus.published.single() as LightningStrike
        assertThat(event.world).isEqualTo(world)
        assertThat(event.position).isEqualTo(position)
        assertThat(event.damageEntities).isTrue
        assertThat(event.atTick).isEqualTo(99L)
    }

    @Test
    fun `strike with damageEntities false still publishes event`() {
        val bus = FakeDomainEventBus()
        val gateway = InMemoryLightningGateway(bus, FakeScheduler())

        gateway.strike(world, position, damageEntities = false)
        val event = bus.published.single() as LightningStrike
        assertThat(event.damageEntities).isFalse
    }
}

class InMemoryFireSpreadControllerTest {

    private val world = WorldId("burnable")
    private val other = WorldId("safe")

    @Test
    fun `default world fire spread is enabled`() {
        val controller = InMemoryFireSpreadController()
        assertThat(controller.isFireSpreadEnabled(world)).isTrue
    }

    @Test
    fun `set persists per world`() {
        val controller = InMemoryFireSpreadController()
        controller.setFireSpread(world, enabled = false)
        assertThat(controller.isFireSpreadEnabled(world)).isFalse
        assertThat(controller.isFireSpreadEnabled(other)).isTrue
    }

    @Test
    fun `spread returns zero ignited blocks in memory`() {
        val controller = InMemoryFireSpreadController()
        val ignited = controller.spreadFire(world, Vec3(0.0, 64.0, 0.0), radius = 5)
        assertThat(ignited).isZero
    }

    @Test
    fun `initial state seeds map`() {
        val controller = InMemoryFireSpreadController(initialState = mapOf(world to false))
        assertThat(controller.isFireSpreadEnabled(world)).isFalse
    }
}
