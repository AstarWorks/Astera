package com.astarworks.astera.domain.model.match

/**
 * Numeric score per team. Game-mode-agnostic — the meaning of "1 point" lives
 * in the rules of the active mode. Immutable; use [withIncrement] for changes.
 */
public data class Score(val perTeam: Map<TeamId, Int>) {

    public fun valueFor(team: TeamId): Int = perTeam[team] ?: 0

    public fun withIncrement(team: TeamId, delta: Int): Score {
        val next = perTeam.toMutableMap()
        next[team] = valueFor(team) + delta
        return copy(perTeam = next)
    }

    /** True iff every team has the same score (including all zero). */
    public val isTied: Boolean
        get() = perTeam.values.toSet().size <= 1

    public companion object {
        public val EMPTY: Score = Score(emptyMap())
    }
}
