package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID

/**
 * Cross-plugin Fake for [IEntitySpawner] (Wave 5l).
 *
 * Mirrors [FakeShopStockRepository]'s shape: single-threaded backing
 * store, `linkedMapOf` for deterministic iteration, plus a recorded
 * `spawned` list so use-case tests can assert spawn ordering.
 *
 * Differs from `RouterEntitySpawner` in two ways:
 *  1. No domain events are published — tests that care about events
 *     wire a real [com.astarworks.astera.testfixtures.FakeDomainEventBus]
 *     into a [RouterEntitySpawner] instead.
 *  2. Every spec is accepted without registry validation.
 *
 * Use this when the test only needs "yes the use-case called spawn with
 * the right args" and doesn't need full router behaviour.
 */
public class FakeEntitySpawner : IEntitySpawner {

    public data class Record(
        public val handle: EntityHandle,
        public val world: WorldId,
        public val position: Vec3,
        public val spec: SpawnSpec,
    )

    private val handles: MutableMap<EntityHandle, Record> = linkedMapOf()

    /** Recorded spawn calls in registration order. */
    public val spawned: MutableList<Record> = mutableListOf()

    /** Recorded despawn calls (handle only). */
    public val despawned: MutableList<EntityHandle> = mutableListOf()

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError> {
        val handle = EntityHandle(id = UUID.randomUUID(), backendId = "fake")
        val record = Record(handle, world, position, spec)
        handles[handle] = record
        spawned += record
        return Result.success(handle)
    }

    override fun despawn(handle: EntityHandle): Result<Unit, SpawnError> {
        val removed = handles.remove(handle)
        return if (removed != null) {
            despawned += handle
            Result.success(Unit)
        } else {
            Result.failure(SpawnError.UnknownTypeKey)
        }
    }

    override fun teleport(handle: EntityHandle, newPos: Vec3): Result<Unit, SpawnError> {
        val existing = handles[handle] ?: return Result.failure(SpawnError.UnknownTypeKey)
        handles[handle] = existing.copy(position = newPos)
        return Result.success(Unit)
    }

    override fun all(): Collection<EntityHandle> = handles.keys.toList()

    /** Test-only: drop all spawned + recorded state. */
    public fun clear() {
        handles.clear()
        spawned.clear()
        despawned.clear()
    }
}
