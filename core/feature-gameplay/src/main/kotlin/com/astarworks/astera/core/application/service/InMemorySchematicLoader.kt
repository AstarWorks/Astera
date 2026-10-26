package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ISchematicLoader
import com.astarworks.astera.core.application.port.outbound.SchematicLoadError
import com.astarworks.astera.core.application.port.outbound.SchematicPlaceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import java.nio.file.Path

/**
 * In-memory Fake [ISchematicLoader] for Wave 5d.
 *
 * Returns pre-registered [Schematic] descriptors from an injectable map (so
 * tests + the Phase C wiring can populate canned data); [place] is a no-op
 * that always succeeds for known worlds and fails for unknown ones.
 *
 * Paper-side `PaperSchematicLoader` (FAWE adapter, ADR-0015 D-path) lands
 * in Wave 6 and replaces this at priority 100 via `ServiceRegistry`.
 *
 * Two ways to register canned schematics:
 *  - Constructor: pass `initial` map
 *  - Runtime: call [register] (and [unregister]) — useful for tests
 *
 * Two ways to declare known worlds:
 *  - Constructor: pass `knownWorlds` set; defaults to "accept everything"
 *  - Runtime: call [registerWorld] / [unregisterWorld]
 *
 * **Not thread-safe** — Astera invariant: all world / match-state mutation
 * runs on the server main thread (per `IScheduler` regional contract). Plain
 * `MutableMap` / `MutableSet` are intentional; concurrent collections would
 * imply support we do not provide.
 */
public class InMemorySchematicLoader(
    initial: Map<Path, Schematic> = emptyMap(),
    knownWorlds: Set<WorldId> = emptySet(),
    private val acceptAnyWorld: Boolean = knownWorlds.isEmpty(),
) : ISchematicLoader {
    private val schematics: MutableMap<Path, Schematic> = initial.toMutableMap()
    private val worlds: MutableSet<WorldId> = knownWorlds.toMutableSet()
    private val placements: MutableList<Placement> = mutableListOf()

    override fun load(path: Path): Result<Schematic, SchematicLoadError> {
        val found = schematics[path] ?: return Result.Failure(SchematicLoadError.NotFound(path))
        return Result.Success(found)
    }

    override fun place(
        schematic: Schematic,
        world: WorldId,
        offset: Vec3,
    ): Result<Unit, SchematicPlaceError> {
        if (!acceptAnyWorld && world !in worlds) {
            return Result.Failure(SchematicPlaceError.UnknownWorld(world))
        }
        placements += Placement(schematic, world, offset)
        return Result.Success(Unit)
    }

    public fun register(path: Path, schematic: Schematic) {
        schematics[path] = schematic
    }

    public fun unregister(path: Path) {
        schematics.remove(path)
    }

    public fun registerWorld(world: WorldId) {
        worlds += world
    }

    public fun unregisterWorld(world: WorldId) {
        worlds -= world
    }

    /** Test-only: inspect placement history (NOT part of [ISchematicLoader] interface). */
    public fun placements(): List<Placement> = placements.toList()

    public data class Placement(val schematic: Schematic, val world: WorldId, val offset: Vec3)
}
