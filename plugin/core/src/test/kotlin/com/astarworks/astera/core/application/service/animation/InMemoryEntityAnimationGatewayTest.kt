package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.entity.EntityAnimationError
import com.astarworks.astera.core.domain.animation.entity.MobAnimationKind
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobAnimationPlayed
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryEntityAnimationGatewayTest {

    private val bus = FakeDomainEventBus()
    private val gateway = InMemoryEntityAnimationGateway(bus, tickClock = { 200L })
    private val mob = EntityHandle(UUID.randomUUID(), "minimal")

    @Test
    fun `playMobAttackAnimation publishes Attack`() {
        gateway.playMobAttackAnimation(mob)
        val events = bus.publishedOfType<MobAnimationPlayed>()
        assertThat(events).hasSize(1)
        assertThat(events.first().handle).isEqualTo(mob)
        assertThat(events.first().kind).isEqualTo(MobAnimationKind.Attack)
        assertThat(events.first().atTick).isEqualTo(200L)
    }

    @Test
    fun `playMobDeathAnimation publishes Death`() {
        gateway.playMobDeathAnimation(mob)
        val event = bus.publishedOfType<MobAnimationPlayed>().single()
        assertThat(event.kind).isEqualTo(MobAnimationKind.Death)
    }

    @Test
    fun `playMobPickupAnimation publishes Pickup with item id`() {
        val item = ItemId("loot-sword")
        gateway.playMobPickupAnimation(mob, item)
        val event = bus.publishedOfType<MobAnimationPlayed>().single()
        assertThat(event.kind).isEqualTo(MobAnimationKind.Pickup(item))
    }

    @Test
    fun `playMobDamageFlash publishes DamageFlash`() {
        gateway.playMobDamageFlash(mob)
        val event = bus.publishedOfType<MobAnimationPlayed>().single()
        assertThat(event.kind).isEqualTo(MobAnimationKind.DamageFlash)
    }

    @Test
    fun `playMobDrownAnimation toggles drowning flag`() {
        gateway.playMobDrownAnimation(mob, true)
        gateway.playMobDrownAnimation(mob, false)
        val events = bus.publishedOfType<MobAnimationPlayed>()
        assertThat(events).hasSize(2)
        assertThat(events[0].kind).isEqualTo(MobAnimationKind.Drown(true))
        assertThat(events[1].kind).isEqualTo(MobAnimationKind.Drown(false))
    }

    @Test
    fun `setMobLookDirection normalises and publishes`() {
        val direction = Vec3(3.0, 0.0, 4.0) // length 5
        gateway.setMobLookDirection(mob, direction)
        val kind = bus.publishedOfType<MobAnimationPlayed>().single().kind
        assertThat(kind).isInstanceOf(MobAnimationKind.LookAt::class.java)
        val normalised = (kind as MobAnimationKind.LookAt).direction
        assertThat(normalised.length()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9))
    }

    @Test
    fun `setMobLookDirection rejects zero vector`() {
        val result = gateway.setMobLookDirection(mob, Vec3.ZERO)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(EntityAnimationError.InvalidDirection::class.java)
    }

    @Test
    fun `setMobLookDirection rejects non-finite vector`() {
        val result = gateway.setMobLookDirection(mob, Vec3(Double.NaN, 0.0, 0.0))
        assertThat(result).isInstanceOf(Result.Failure::class.java)
    }
}
