package com.astarworks.astera.match.domain.generator

import com.astarworks.astera.core.domain.match.TeamId

/**
 * Ownership of a [StarGenerator].
 *
 * Wave 5c: most public-arena generators are [Neutral] (anyone may harvest);
 * Phase 3+ Siege Warfare introduces [Team]-owned generators that only one
 * team may energize / collect from. The sealed split keeps the game-mode
 * listener exhaustive without a sentinel "no team" string.
 */
public sealed class Affiliation {
    /** Generator owned by a specific team. Only members of [teamId] may collect. */
    public data class Team(val teamId: TeamId) : Affiliation()

    /** Generator open to any participant. Default for Phase 1-2 arena samples. */
    public data object Neutral : Affiliation()
}
