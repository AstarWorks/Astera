package com.astarworks.astera.testfixtures.item

import com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway
import com.astarworks.astera.core.application.port.outbound.item.ItemEntityError
import com.astarworks.astera.core.application.port.outbound.item.ItemEntitySnapshot
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemEntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Test-fixture [IItemEntityGateway] (Wave 5complete, ADR-0117).
 *
 * Single-threaded, no scheduler dependency. Exposes the active map +
 * spawn / remove call ledgers so listener tests can assert what the
 * gateway saw.
 */
public class FakeItemEntityGateway : IItemEntityGateway {

    public data class SpawnRecord(
        val world: WorldId,
        val position: Vec3,
        val itemId: ItemId,
        val count: Int,
        val lifetimeSeconds: Int?,
        val handle: ItemEntityHandle,
    )

    public val spawnCalls: MutableList<SpawnRecord> = mutableListOf()
    public val removeCalls: MutableList<ItemEntityHandle> = mutableListOf()

    private val store: MutableMap<ItemEntityHandle, ItemEntitySnapshot> = mutableMapOf()

    /** Force a deterministic next-handle (for assertions). `null` → random. */
    public var nextHandle: ItemEntityHandle? = null

    override fun spawnItemEntity(
        world: WorldId,
        position: Vec3,
        itemId: ItemId,
        count: Int,
        lifetimeSeconds: Int?,
    ): Result<ItemEntityHandle, ItemEntityError> {
        if (count <= 0) return Result.failure(ItemEntityError.InvalidCount(count))
        val handle = nextHandle ?: ItemEntityHandle.random()
        nextHandle = null
        store[handle] = ItemEntitySnapshot(
            handle = handle,
            world = world,
            position = position,
            itemId = itemId,
            count = count,
        )
        spawnCalls += SpawnRecord(world, position, itemId, count, lifetimeSeconds, handle)
        return Result.success(handle)
    }

    override fun removeItemEntity(handle: ItemEntityHandle): Result<Unit, ItemEntityError> {
        removeCalls += handle
        if (store.remove(handle) == null) {
            return Result.failure(ItemEntityError.UnknownHandle(handle))
        }
        return Result.success(Unit)
    }

    override fun findNearby(
        world: WorldId,
        position: Vec3,
        radius: Double,
    ): Collection<ItemEntityHandle> {
        val r2 = radius * radius
        return store.values
            .filter { it.world == world }
            .filter { it.position.distanceSquared(position) <= r2 }
            .map { it.handle }
    }

    override fun peek(handle: ItemEntityHandle): ItemEntitySnapshot? = store[handle]

    public fun reset() {
        spawnCalls.clear()
        removeCalls.clear()
        store.clear()
        nextHandle = null
    }
}
