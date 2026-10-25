package com.astarworks.astera.core.application.service.item

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.item.IItemEntityGateway
import com.astarworks.astera.core.application.port.outbound.item.ItemEntityError
import com.astarworks.astera.core.application.port.outbound.item.ItemEntitySnapshot
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemEntityHandle
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory default impl of [IItemEntityGateway] (Wave 5complete, ADR-0117).
 *
 * Stores every spawned entity in a `ConcurrentHashMap` keyed by handle. The
 * `findNearby` query is O(N entities in the world) — acceptable for Phase 1
 * PoC where match-active loot density is small (~tens of items at any time).
 * Wave 6b's Paper adapter delegates to Bukkit's chunk-based entity index for
 * O(1)-ish chunk lookup.
 *
 * # Lifetime
 *
 * When [scheduler] is non-null, [spawnItemEntity] schedules a one-shot
 * despawn via [IScheduler.runLaterTicks] (`lifetimeSeconds * 20` ticks). The
 * scheduler is optional so unit tests can drive the gateway without a real
 * scheduler.
 *
 * # Thread-safety
 *
 * Backed by `ConcurrentHashMap` — multiple readers + writers may interleave
 * safely. `findNearby` returns a defensive copy snapshot.
 */
public class InMemoryItemEntityGateway(
    private val scheduler: IScheduler? = null,
) : IItemEntityGateway {

    private data class Record(
        val handle: ItemEntityHandle,
        val world: WorldId,
        val position: Vec3,
        val itemId: ItemId,
        val count: Int,
        val despawnTask: IScheduler.TaskHandle? = null,
    )

    private val entities: ConcurrentHashMap<ItemEntityHandle, Record> = ConcurrentHashMap()

    override fun spawnItemEntity(
        world: WorldId,
        position: Vec3,
        itemId: ItemId,
        count: Int,
        lifetimeSeconds: Int?,
    ): Result<ItemEntityHandle, ItemEntityError> {
        if (count <= 0) return Result.failure(ItemEntityError.InvalidCount(count))

        val handle = ItemEntityHandle.random()
        val effectiveLifetime = lifetimeSeconds ?: IItemEntityGateway.DEFAULT_LIFETIME_SECONDS
        val task: IScheduler.TaskHandle? = scheduler
            ?.takeIf { effectiveLifetime > 0 }
            ?.runLaterTicks((effectiveLifetime.toLong()) * TICKS_PER_SECOND) {
                entities.remove(handle)
            }

        val record = Record(
            handle = handle,
            world = world,
            position = position,
            itemId = itemId,
            count = count,
            despawnTask = task,
        )
        entities[handle] = record
        return Result.success(handle)
    }

    override fun removeItemEntity(handle: ItemEntityHandle): Result<Unit, ItemEntityError> {
        val existing = entities.remove(handle)
            ?: return Result.failure(ItemEntityError.UnknownHandle(handle))
        existing.despawnTask?.cancel()
        return Result.success(Unit)
    }

    override fun findNearby(
        world: WorldId,
        position: Vec3,
        radius: Double,
    ): Collection<ItemEntityHandle> {
        val radiusSquared = radius * radius
        return entities.values
            .asSequence()
            .filter { it.world == world }
            .filter { it.position.distanceSquared(position) <= radiusSquared }
            .map { it.handle }
            .toList()
    }

    override fun peek(handle: ItemEntityHandle): ItemEntitySnapshot? {
        val rec = entities[handle] ?: return null
        return ItemEntitySnapshot(
            handle = rec.handle,
            world = rec.world,
            position = rec.position,
            itemId = rec.itemId,
            count = rec.count,
        )
    }

    /** Test helper: drop all state. */
    public fun reset() {
        entities.values.forEach { it.despawnTask?.cancel() }
        entities.clear()
    }

    /** Snapshot of every active entity (test diagnostics). */
    public fun activeCount(): Int = entities.size

    public companion object {
        private const val TICKS_PER_SECOND: Long = 20L
    }
}
