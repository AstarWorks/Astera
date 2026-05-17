package com.astarworks.astera.match.domain.match

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.domain.stage.StageId

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

    /** True if [playerId] is already registered in any team. */
    public fun hasParticipant(playerId: PlayerId): Boolean =
        teams.any { playerId in it.members }

    /**
     * Returns a copy of this match with [playerId] added to [teamId].
     * If [teamId] is null the player is added to the first available team.
     * Does not enforce max-team-size — caller is responsible.
     */
    public fun addParticipant(playerId: PlayerId, teamId: TeamId?): Match {
        val targetId = teamId ?: teams.first().id
        val updatedTeams = teams.map { team ->
            if (team.id == targetId) team.copy(members = team.members + playerId) else team
        }
        return copy(teams = updatedTeams)
    }

    /** Returns a copy with [phase] replaced. */
    public fun withPhase(newPhase: MatchPhase): Match = copy(phase = newPhase)
}
