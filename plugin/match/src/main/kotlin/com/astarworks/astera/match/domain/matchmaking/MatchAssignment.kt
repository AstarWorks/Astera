package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId

/**
 * A match the lobby server formed and asks the game server to host (ADR-0179).
 *
 * [id] only correlates the lobby's request with the game server's answers; the
 * game server mints its own [MatchId] when it starts the match. [teams] holds
 * the formation order: team `i` lands on the match's `i`-th team.
 */
public data class MatchAssignment(
    val id: MatchId,
    val stageId: StageId,
    val modeKey: String?,
    val teams: List<List<PlayerId>>,
    /** An admin forced the formation past the roster floor: the game server waives its own floor too. */
    val forced: Boolean = false,
) {
    init {
        require(teams.isNotEmpty()) { "A match assignment needs at least one team" }
    }

    val players: List<PlayerId> get() = teams.flatten()
}

/** The game server's answers to a [MatchAssignment] (ADR-0179). */
public sealed interface AssignmentStatus {
    public val assignmentId: MatchId

    /** The game server will host it; the lobby may now transfer the players. */
    public data class Accepted(override val assignmentId: MatchId) : AssignmentStatus

    /** The game server cannot host it (busy / start failed); the lobby re-queues the players. */
    public data class Rejected(override val assignmentId: MatchId, val reason: String) : AssignmentStatus

    /** The match is over and the players are on their way back; the game server is free again. */
    public data class Ended(override val assignmentId: MatchId) : AssignmentStatus
}
