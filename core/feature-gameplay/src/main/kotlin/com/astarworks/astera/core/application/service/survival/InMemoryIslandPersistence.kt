package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.survival.IIslandPersistence
import com.astarworks.astera.core.application.port.outbound.survival.IslandPersistenceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory / fake [IIslandPersistence] (Wave 5pve-survival / ADR-0107).
 *
 * Foundation wave does not have a Paper-side schematic snapshotter — that
 * lands in Wave 6 alongside the FAWE adapter. This impl satisfies the
 * port contract by:
 *
 *  - `save` — mints a deterministic [Schematic] handle pointing at a
 *    conventional path (`islands/<id>.schem`) and remembers the islandId.
 *  - `load` — returns the previously-`save`d handle if any, otherwise
 *    `NotFound`.
 *  - `place` — delegates to the injected [ISchematicLoader] (default fake
 *    in-memory loader) so the wiring path is exercised in tests.
 *  - `exists` — boolean lookup against the saved-id set.
 *
 * Tests can also inject a custom [pathFor] to assert against bespoke
 * schematic paths without spinning the file system.
 */
public class InMemoryIslandPersistence(
    private val schematicLoader: ISchematicLoader,
    private val defaultSize: Vec3 = Vec3(64.0, 64.0, 64.0),
    private val schematicVersion: Int = 3,
    private val pathFor: (IslandId) -> String = { id -> "islands/${id.value}.schem" },
) : IIslandPersistence {

    private val snapshots: ConcurrentHashMap<IslandId, Schematic> = ConcurrentHashMap()

    override fun save(islandId: IslandId, world: WorldId): Result<Schematic, IslandPersistenceError> {
        val schematic = Schematic(
            path = pathFor(islandId),
            size = defaultSize,
            version = schematicVersion,
        )
        snapshots[islandId] = schematic
        return Result.success(schematic)
    }

    override fun load(islandId: IslandId): Result<Schematic, IslandPersistenceError> {
        val s = snapshots[islandId] ?: return Result.failure(IslandPersistenceError.NotFound(islandId))
        return Result.success(s)
    }

    override fun place(
        islandId: IslandId,
        world: WorldId,
        offset: Vec3,
    ): Result<Unit, IslandPersistenceError> {
        val schematic = snapshots[islandId]
            ?: return Result.failure(IslandPersistenceError.NotFound(islandId))
        return when (val placeResult = schematicLoader.place(schematic, world, offset)) {
            is Result.Success -> Result.success(Unit)
            is Result.Failure -> Result.failure(
                IslandPersistenceError.PlaceFailed(islandId, world, placeResult.error.toString()),
            )
        }
    }

    override fun exists(islandId: IslandId): Boolean = snapshots.containsKey(islandId)
}
