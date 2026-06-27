package com.astarworks.astera.match.domain.tournament

/**
 * Identity of a tournament bracket (Wave 7b, ADR-0037).
 *
 * Lower-kebab-case to match the project's other string ids
 * ([com.astarworks.astera.core.domain.match.MatchId] /
 * [com.astarworks.astera.core.domain.match.TeamId]) and to be safe as a
 * Postgres primary-key string.
 */
@JvmInline
public value class TournamentId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "TournamentId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}
