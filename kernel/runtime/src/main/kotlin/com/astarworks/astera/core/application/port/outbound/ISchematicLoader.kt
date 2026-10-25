package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.Schematic
import com.astarworks.astera.core.domain.world.WorldId
import java.nio.file.Path

/**
 * Outbound port: load and place WorldEdit `.schem` schematics (Wave 5d).
 *
 * Wave 5d ships `InMemorySchematicLoader` (Fake). The Paper-side
 * `PaperSchematicLoader` (FAWE adapter, ADR-0015 D-path) lands in Wave 6.
 */
public interface ISchematicLoader {

    /** Load the schematic at [path]. Returns failure if missing or version-unsupported. */
    public fun load(path: Path): Result<Schematic, SchematicLoadError>

    /**
     * Place [schematic] into [world] with its origin at [offset]. The block
     * payload is resolved lazily (the [Schematic] domain type only carries
     * the path + bounding box + format version).
     */
    public fun place(
        schematic: Schematic,
        world: WorldId,
        offset: Vec3,
    ): Result<Unit, SchematicPlaceError>
}

/** Loader-side failures (file missing, malformed, unsupported version). */
public sealed class SchematicLoadError {
    public data class NotFound(val path: Path) : SchematicLoadError()
    public data class InvalidVersion(val path: Path, val version: Int) : SchematicLoadError()
    public data class Malformed(val path: Path, val detail: String) : SchematicLoadError()
}

/** Placement-side failures (target world missing, write blocked, etc.). */
public sealed class SchematicPlaceError {
    public data class UnknownWorld(val world: WorldId) : SchematicPlaceError()
    public data class WriteFailed(val detail: String) : SchematicPlaceError()
}
