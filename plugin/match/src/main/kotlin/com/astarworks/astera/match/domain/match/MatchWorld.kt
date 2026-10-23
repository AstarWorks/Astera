package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * The world a match is played in (Wave 5d, reshaped by ADR-0246).
 *
 * Externalized from `core.domain.match.Match` so Core stays unaware of
 * Match-plugin-side world bookkeeping. Each active match owns at most one
 * `MatchWorld`; the repository maps `matchId -> MatchWorld`. [worldId] is the
 * stage's real world (the void `arenas` world for sky-island stages), [stageId]
 * the arena that was built there.
 *
 * `CreateMatchWorldUseCase` populates the repository on `MatchStarted`;
 * `DestroyMatchWorldUseCase` cleans up on `MatchEnded`.
 */
public data class MatchWorld(
    val matchId: MatchId,
    val worldId: WorldId,
    val stageId: StageId,
)
