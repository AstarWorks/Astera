package com.astarworks.astera.core.application.service.animation

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.animation.player.CustomAnimationId
import com.astarworks.astera.core.domain.animation.player.PlayerAnimationError
import com.astarworks.astera.core.domain.animation.player.PlayerHand
import com.astarworks.astera.core.domain.animation.player.SitTarget
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.PlayerEquipped
import com.astarworks.astera.core.domain.event.PlayerSitChanged
import com.astarworks.astera.core.domain.event.PlayerSleepChanged
import com.astarworks.astera.core.domain.event.PlayerSneakChanged
import com.astarworks.astera.core.domain.event.PlayerSwungArm
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.EquipSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerAnimationGatewayTest {

    private val bus = FakeDomainEventBus()
    private val gateway = InMemoryPlayerAnimationGateway(bus, tickClock = { 100L })
    private val player = PlayerId(UUID.randomUUID())
    private val world = WorldId("world")

    @Test
    fun `swingArm publishes PlayerSwungArm`() {
        val result = gateway.swingArm(player, PlayerHand.MainHand)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val events = bus.publishedOfType<PlayerSwungArm>()
        assertThat(events).hasSize(1)
        assertThat(events.first().playerId).isEqualTo(player)
        assertThat(events.first().hand).isEqualTo(PlayerHand.MainHand)
        assertThat(events.first().atTick).isEqualTo(100L)
    }

    @Test
    fun `applyEquipAnimation publishes PlayerEquipped`() {
        val item = ItemId("test-sword")
        val result = gateway.applyEquipAnimation(player, EquipSlot.MAINHAND, item)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        val events = bus.publishedOfType<PlayerEquipped>()
        assertThat(events).hasSize(1)
        assertThat(events.first().slot).isEqualTo(EquipSlot.MAINHAND)
        assertThat(events.first().item).isEqualTo(item)
    }

    @Test
    fun `setSneaking publishes only on state change`() {
        gateway.setSneaking(player, true)
        gateway.setSneaking(player, true)
        gateway.setSneaking(player, false)
        gateway.setSneaking(player, false)
        val events = bus.publishedOfType<PlayerSneakChanged>()
        assertThat(events).hasSize(2)
        assertThat(events.map { it.sneaking }).containsExactly(true, false)
    }

    @Test
    fun `setSneaking is idempotent on no-change`() {
        gateway.setSneaking(player, true)
        bus.published.clear()
        gateway.setSneaking(player, true)
        assertThat(bus.publishedOfType<PlayerSneakChanged>()).isEmpty()
        assertThat(gateway.sneakState(player)).isTrue()
    }

    @Test
    fun `setSleeping publishes only on state change`() {
        val bed = Vec3(0.5, 64.0, 0.5)
        gateway.setSleeping(player, world, bed, true)
        gateway.setSleeping(player, world, bed, true)
        gateway.setSleeping(player, world, bed, false)
        val events = bus.publishedOfType<PlayerSleepChanged>()
        assertThat(events).hasSize(2)
    }

    @Test
    fun `setSitting publishes on entity vehicle target`() {
        val vehicle = EntityHandle(UUID.randomUUID(), "test")
        gateway.setSitting(player, SitTarget.Vehicle(vehicle))
        val events = bus.publishedOfType<PlayerSitChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.first().target).isInstanceOf(SitTarget.Vehicle::class.java)
        assertThat(gateway.sitState(player)).isEqualTo(SitTarget.Vehicle(vehicle))
    }

    @Test
    fun `setSitting None on a standing player is a no-op`() {
        gateway.setSitting(player, SitTarget.None)
        assertThat(bus.publishedOfType<PlayerSitChanged>()).isEmpty()
        assertThat(gateway.sitState(player)).isEqualTo(SitTarget.None)
    }

    @Test
    fun `setSitting transition records and publishes`() {
        val vehicle = EntityHandle(UUID.randomUUID(), "test")
        gateway.setSitting(player, SitTarget.Vehicle(vehicle))
        gateway.setSitting(player, SitTarget.None)
        assertThat(bus.publishedOfType<PlayerSitChanged>()).hasSize(2)
        assertThat(gateway.sitState(player)).isEqualTo(SitTarget.None)
    }

    @Test
    fun `playCustomAnimation rejects negative duration`() {
        val result = gateway.playCustomAnimation(player, CustomAnimationId("wave-emote"), -1L)
        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(PlayerAnimationError.InvalidDuration::class.java)
    }

    @Test
    fun `playCustomAnimation accepts zero and positive duration`() {
        assertThat(gateway.playCustomAnimation(player, CustomAnimationId("wave"), 0L))
            .isInstanceOf(Result.Success::class.java)
        assertThat(gateway.playCustomAnimation(player, CustomAnimationId("wave"), 40L))
            .isInstanceOf(Result.Success::class.java)
    }
}
