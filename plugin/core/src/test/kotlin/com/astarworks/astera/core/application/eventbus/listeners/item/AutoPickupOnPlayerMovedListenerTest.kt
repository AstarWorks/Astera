package com.astarworks.astera.core.application.eventbus.listeners.item

import com.astarworks.astera.core.application.service.inventory.InMemoryInventoryMutator
import com.astarworks.astera.core.domain.event.LootPickedUp
import com.astarworks.astera.core.domain.event.PlayerMoved
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.item.FakeItemEntityGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5complete (ADR-0117) — auto-pickup integration test:
 * PlayerMoved → nearby items → inventory.give + despawn + LootPickedUp.
 */
class AutoPickupOnPlayerMovedListenerTest {

    private val world = WorldId("test-world")
    private val player = PlayerId(UUID.randomUUID())
    private val itemId = ItemId("amethyst-sword")

    @Test
    fun `nearby item is picked up despawned and event published`() {
        val gateway = FakeItemEntityGateway()
        val mutator = InMemoryInventoryMutator()
        val bus = FakeDomainEventBus()
        val listener = AutoPickupOnPlayerMovedListener(
            itemEntityGateway = gateway,
            inventoryMutator = mutator,
            eventBus = bus,
            pickupRadius = 2.0,
        )

        gateway.spawnItemEntity(world, Vec3(10.0, 64.0, 10.0), itemId, 3)

        listener.handle(
            PlayerMoved(
                playerId = player,
                from = Vec3(9.0, 64.0, 10.0),
                to = Vec3(10.5, 64.0, 10.0),
                world = world,
                atTick = 200L,
            ),
        )

        // Despawn recorded
        assertThat(gateway.removeCalls).hasSize(1)
        // Inventory mutated
        assertThat(mutator.count(player, itemId)).isEqualTo(3)
        // LootPickedUp event published
        val pickups = bus.published.filterIsInstance<LootPickedUp>()
        assertThat(pickups).hasSize(1)
        assertThat(pickups[0].itemId).isEqualTo(itemId)
        assertThat(pickups[0].count).isEqualTo(3)
        assertThat(pickups[0].playerId).isEqualTo(player)
    }

    @Test
    fun `far away item is not picked up`() {
        val gateway = FakeItemEntityGateway()
        val mutator = InMemoryInventoryMutator()
        val bus = FakeDomainEventBus()
        val listener = AutoPickupOnPlayerMovedListener(gateway, mutator, bus, pickupRadius = 1.5)

        gateway.spawnItemEntity(world, Vec3(100.0, 64.0, 0.0), itemId, 1)

        listener.handle(
            PlayerMoved(
                playerId = player,
                from = Vec3(0.0, 64.0, 0.0),
                to = Vec3(1.0, 64.0, 0.0),
                world = world,
                atTick = 200L,
            ),
        )

        assertThat(gateway.removeCalls).isEmpty()
        assertThat(mutator.count(player, itemId)).isEqualTo(0)
        assertThat(bus.published.filterIsInstance<LootPickedUp>()).isEmpty()
    }

    @Test
    fun `cross-world item is ignored`() {
        val gateway = FakeItemEntityGateway()
        val mutator = InMemoryInventoryMutator()
        val bus = FakeDomainEventBus()
        val listener = AutoPickupOnPlayerMovedListener(gateway, mutator, bus, pickupRadius = 2.0)

        gateway.spawnItemEntity(WorldId("other-world"), Vec3(0.0, 0.0, 0.0), itemId, 1)

        listener.handle(
            PlayerMoved(
                playerId = player,
                from = Vec3(0.0, 0.0, 0.0),
                to = Vec3(0.0, 0.0, 0.0),
                world = world,
                atTick = 0L,
            ),
        )

        assertThat(gateway.removeCalls).isEmpty()
        assertThat(mutator.count(player, itemId)).isEqualTo(0)
    }
}
