package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Entity lifecycle port; NPC requests route to the single NpcAdapter (ADR-0264). */
public interface IEntitySpawner {

    /**
     * Spawn an entity according to [spec] at [position] in [world].
     *
     * Returns [SpawnError.UnknownTypeKey] if no adapter recognises the
     * type key, [SpawnError.NoSpawnableBackend] if no available adapter
     * supports the spec variant, or [SpawnError.WorldNotLoaded] if the
     * target world isn't currently loaded.
     */
    public fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError>

    /**
     * Remove the entity identified by [handle]. Idempotent — despawning an
     * already-despawned handle returns [SpawnError.UnknownTypeKey] (recycled
     * to mean "I don't know that handle anymore" rather than introducing a
     * new error variant).
     */
    public fun despawn(handle: EntityHandle): Result<Unit, SpawnError>

    /**
     * Move the entity to [newPos] in its current world.
     *
     * Cross-world teleport is intentionally NOT supported here — use
     * [IPlayerMovementGateway] for players, or despawn-and-respawn for
     * other entities. (Avoiding cross-world teleport keeps the spawner's
     * world-bookkeeping simple.)
     */
    public fun teleport(handle: EntityHandle, newPos: Vec3): Result<Unit, SpawnError>

    /**
     * Snapshot of every currently-spawned handle. O(n) — intended for
     * admin / debug listing, not hot-path lookups.
     */
    public fun all(): Collection<EntityHandle>
}
