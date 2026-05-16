package com.astarworks.astera.domain.model.match

import com.astarworks.astera.domain.model.stage.StageId

/**
 * A single match instance.
 *
 * Phase 1 / Phase 2 do not persist matches; this type exists so Phase 3
 * (Siege Warfare) use cases have something to operate on without touching the
 * domain model again. Behavioral transitions land in Phase 3.
 */
public data class Match(
    val id: MatchId,
    val phase: MatchPhase,
    val teams: List<Team>,
    val score: Score,
    val stageId: StageId,
    val startedAtTick: Long,
) {
    init {
        require(teams.isNotEmpty()) { "A match must have at least one team" }
        require(startedAtTick >= 0) { "startedAtTick must be >= 0" }
    }
}
