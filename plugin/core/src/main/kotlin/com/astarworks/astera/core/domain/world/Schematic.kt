package com.astarworks.astera.core.domain.world

import com.astarworks.astera.core.domain.geometry.Vec3

/**
 * Reference to a saved chunk of block geometry (Wave 5d, WorldEdit `.schem`
 * compatible).
 *
 * Wave 5d ships the domain type + `ISchematicLoader` port + an in-memory
 * Fake. The Paper-side `PaperSchematicLoader` (FAWE adapter) lands in
 * Wave 6 per ADR-0015 D-path governance.
 *
 * `path` is a relative-or-absolute filesystem path **as a String** —
 * vendor-neutral, ADR-0001 / 0002 / Konsist (`*.domain.*` may not import
 * `java.nio.*`). The application port `ISchematicLoader` wraps it with
 * `java.nio.file.Path.of(...)` at the I/O boundary. Preserved (not the
 * in-memory block payload) so `MatchWorld` references a schematic by path
 * and re-resolves lazily — no stale schematic bytes across reloads.
 *
 * `size` is the bounding box in blocks; useful for stage / region sizing
 * without forcing a load. `version` is the on-disk format version (`.schem`
 * sponge format = 2 or 3).
 */
public data class Schematic(
    val path: String,
    val size: Vec3,
    val version: Int,
) {
    init {
        require(path.isNotBlank()) { "Schematic.path must not be blank" }
        require(version > 0) { "Schematic.version must be > 0, was $version" }
    }
}
