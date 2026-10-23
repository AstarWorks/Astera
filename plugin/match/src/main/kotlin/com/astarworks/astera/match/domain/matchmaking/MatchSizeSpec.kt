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
    /**
     * Fewest queued players `tryFormMatch` accepts (WP8). Defaults to a full
     * roster ([targetPlayers]); a lower value lets a partly filled queue
     * start, with teams balanced to within one player of each other (a
     * 1-player floor is the playtest setting).
     */
    val minPlayers: Int = teamCount * playersPerTeam,
    /**
     * PvP floor: a match forms only when every team received at least one player (a 3-team lane needs
     * three sides, not just three bodies). An admin force start (explicit `minPlayers`) bypasses it.
     */
    val requireAllTeams: Boolean = false,
    /**
     * PvE (ADR-0249): everybody fights on the first team, up to [playersPerTeam] players; the other
     * teams exist only so the stage and the result screen have an opposing side (an empty one).
     */
    val cooperative: Boolean = false,
) {
    init {
        require(teamCount >= 2) { "MatchSizeSpec.teamCount must be >= 2; got $teamCount" }
        require(playersPerTeam >= 1) { "MatchSizeSpec.playersPerTeam must be >= 1; got $playersPerTeam" }
        require(minPlayers in 1..targetPlayers) {
            "MatchSizeSpec.minPlayers must be in 1..$targetPlayers; got $minPlayers"
        }
    }

    /** Total players a full match of this size holds (the roster cap). */
    public val targetPlayers: Int get() = teamCount * playersPerTeam

    public companion object {
        /** Phase 1 PoC floor: 2 teams × 1 player. */
        public val DEFAULT: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 1)
        public val ONE_V_ONE: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 1)
        public val TWO_V_TWO: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 2)
        public val THREE_V_THREE: MatchSizeSpec = MatchSizeSpec(teamCount = 2, playersPerTeam = 3)
    }
}
