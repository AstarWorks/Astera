package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.application.port.outbound.item.ItemEntityError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemEntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Wave 5complete (ADR-0117) — in-memory IItemEntityGateway impl test.
 */
class InMemoryItemEntityGatewayTest {

    private val world = WorldId("test-world")
    private val itemId = ItemId("amethyst-sword")

    @Test
    fun `spawn returns a handle and findNearby surfaces it within radius`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)
        val spawn = gateway.spawnItemEntity(world, Vec3(10.0, 64.0, 20.0), itemId, 3)
        assertThat(spawn).isInstanceOf(Result.Success::class.java)
        val handle = (spawn as Result.Success).value

        val nearby = gateway.findNearby(world, Vec3(10.5, 64.0, 20.0), radius = 1.0)
        assertThat(nearby).containsExactly(handle)
    }

    @Test
    fun `findNearby returns empty for points outside radius`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)
        gateway.spawnItemEntity(world, Vec3(0.0, 0.0, 0.0), itemId, 1)

        val nearby = gateway.findNearby(world, Vec3(100.0, 0.0, 0.0), radius = 2.0)
        assertThat(nearby).isEmpty()
    }

    @Test
    fun `findNearby is world-scoped`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)
        gateway.spawnItemEntity(world, Vec3(0.0, 0.0, 0.0), itemId, 1)

        val nearby = gateway.findNearby(WorldId("other-world"), Vec3(0.0, 0.0, 0.0), radius = 5.0)
        assertThat(nearby).isEmpty()
    }

    @Test
    fun `peek returns the spawn record`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)
        val handle = (gateway.spawnItemEntity(world, Vec3(1.0, 2.0, 3.0), itemId, 5) as Result.Success).value

        val snapshot = gateway.peek(handle)
        assertThat(snapshot).isNotNull
        assertThat(snapshot!!.itemId).isEqualTo(itemId)
        assertThat(snapshot.count).isEqualTo(5)
        assertThat(snapshot.position).isEqualTo(Vec3(1.0, 2.0, 3.0))
        assertThat(snapshot.world).isEqualTo(world)
    }

    @Test
    fun `removeItemEntity drops the entry and returns Success`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)
        val handle = (gateway.spawnItemEntity(world, Vec3.ZERO, itemId, 1) as Result.Success).value

        val outcome = gateway.removeItemEntity(handle)
        assertThat(outcome).isInstanceOf(Result.Success::class.java)
        assertThat(gateway.activeCount()).isEqualTo(0)
        assertThat(gateway.peek(handle)).isNull()
    }

    @Test
    fun `removeItemEntity returns UnknownHandle when handle is foreign`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)

        val outcome = gateway.removeItemEntity(ItemEntityHandle.random())
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        val err = (outcome as Result.Failure).error
        assertThat(err).isInstanceOf(ItemEntityError.UnknownHandle::class.java)
    }

    @Test
    fun `spawnItemEntity rejects non-positive counts`() {
        val gateway = InMemoryItemEntityGateway(scheduler = null)

        val outcome = gateway.spawnItemEntity(world, Vec3.ZERO, itemId, 0)
        assertThat(outcome).isInstanceOf(Result.Failure::class.java)
        assertThat((outcome as Result.Failure).error).isInstanceOf(ItemEntityError.InvalidCount::class.java)
    }
}
