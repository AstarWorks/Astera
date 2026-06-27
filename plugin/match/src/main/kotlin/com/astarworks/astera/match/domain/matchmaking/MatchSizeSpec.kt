package com.astarworks.astera.match.domain.matchmaking

/**
 * Per-mode match dimensions (Wave 7b, ADR-0037).
 *
 * `tryFormMatch` reads the spec for the queueing [MatchmakingMode] to decide
 * how many teams to assemble and how many players each team needs. Splitting
 * this out of [com.astarworks.astera.match.application.service.matchmaking.InMemoryMatchmaker]'s
 * constructor lets one matchmaker serve 1v1, 2v2, … queues simultaneously
 * (`sizeByMode`), instead of a single fixed floor.
 */
public data class MatchSizeSpec(
    val teamCount: Int,
    val playersPerTeam: Int,
) {
    init {
        require(teamCount >= 2) { "MatchSizeSpec.teamCount must be >= 2; got $teamCount" }
        require(playersPerTeam >= 1) { "MatchSizeSpec.playersPerTeam must be >= 1; got $playersPerTeam" }
    }

    /** Total players a full match of this size needs. */
    public val targetPlayers: Int get() = teamCount * playersPerTeam

    public companion object {
        /** Phase 1 PoC floor: 2 teams × 1 player. */
        public val DEFAULT: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 1)
        public val ONE_V_ONE: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 1)
        public val TWO_V_TWO: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 2)
        public val THREE_V_THREE: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 3)
    }
}
