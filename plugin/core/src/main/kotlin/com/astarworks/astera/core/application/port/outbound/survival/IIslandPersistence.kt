package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Outbound port: schematic-based island state save / load (Wave
 * 5pve-survival / ADR-0107).
 *
 * Separated from [com.astarworks.astera.core.application.port.outbound.ISchematicLoader]
 * because the island use case needs a higher-level operation:
 *
 *  - `save(islandId, world)` — snapshot the live island world to a
 *    `island_<id>.schem` blob; emits the resulting [Schematic] handle.
 *  - `load(islandId)` — locate the canonical schematic for [islandId]
 *    (Phase 1: filesystem path derived from the id; Phase 4: pluggable
 *    cold-storage / S3 backend).
 *  - `place(islandId, world, offset)` — convenience that chains
 *    `load(islandId)` + `ISchematicLoader.place`.
 *
 * The persistence plugin overlays this with a Postgres-blob impl at
 * priority=100 (Phase 4 — foundation wave ships the filesystem default).
 */
public interface IIslandPersistence {

    /** Save the current island world snapshot to its canonical location. */
    public fun save(islandId: IslandId, world: WorldId): Result<Schematic, IslandPersistenceError>

    /** Load the saved schematic handle for [islandId] (does not place it). */
    public fun load(islandId: IslandId): Result<Schematic, IslandPersistenceError>

    /** Convenience: [load] + place into [world] at [offset]. */
    public fun place(
        islandId: IslandId,
        world: WorldId,
        offset: Vec3,
    ): Result<Unit, IslandPersistenceError>

    /** Whether a saved snapshot exists for [islandId]. */
    public fun exists(islandId: IslandId): Boolean
}

/** Persistence failures. */
public sealed class IslandPersistenceError {
    public data class NotFound(val islandId: IslandId) : IslandPersistenceError()
    public data class SaveFailed(val islandId: IslandId, val detail: String) : IslandPersistenceError()
    public data class LoadFailed(val islandId: IslandId, val detail: String) : IslandPersistenceError()
    public data class PlaceFailed(val islandId: IslandId, val world: WorldId, val detail: String) : IslandPersistenceError()
}
