package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Per-match world reference (Wave 5d).
 *
 * Externalized from `core.domain.match.Match` so Core stays unaware of
 * Match-plugin-side world isolation. Each active match owns at most one
 * `MatchWorld`; the repository maps `matchId → MatchWorld`.
 *
 * `sourceSchematicPath` is a filesystem path **as a String** — Konsist
 * forbids `java.nio.*` inside `*.domain.*` (ADR-0001 / 0002). Stored as a
 * path (not the full `Schematic` data class) so the schematic bytes are
 * resolved lazily through `ISchematicLoader` on read — avoids holding
 * stale block payloads across reloads. The application layer wraps the
 * string with `java.nio.file.Path.of(...)` at the I/O boundary.
 *
 * `CreateMatchWorldUseCase` populates the repository on `MatchStarted`;
 * `DestroyMatchWorldUseCase` cleans up on `MatchEnded`.
 */
public data class MatchWorld(
    val matchId: MatchId,
    val worldId: WorldId,
    val sourceSchematicPath: String,
) {
    init {
        require(sourceSchematicPath.isNotBlank()) {
            "MatchWorld.sourceSchematicPath must not be blank"
        }
    }
}
