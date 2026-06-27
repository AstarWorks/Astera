package com.astarworks.astera.stats.domain.season

import java.time.Instant

/**
 * Identity of a competitive season (Wave 7b, ADR-0037).
 *
 * Lower-kebab-case so it is safe as a leaderboard partition key and Postgres
 * primary key (e.g. `season-2026-summer`).
 */
@JvmInline
public value class SeasonId(public val value: String) {
    init {
        require(VALID.matches(value)) {
            "SeasonId must be lower-kebab-case (a-z, 0-9, -): '$value'"
        }
    }

    override fun toString(): String = value

    public companion object {
        private val VALID = Regex("^[a-z][a-z0-9-]*$")
    }
}

/**
 * A competitive season window (Wave 7b, ADR-0037). [endedAt] is null while the
 * season is active; `ResetSeasonUseCase` (W3) closes the current season and
 * opens a new one. Leaderboards are partitioned by [SeasonId], so a reset
 * starts a fresh ranking without deleting prior history.
 */
public data class Season(
    val id: SeasonId,
    val label: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
) {
    init {
        require(label.isNotBlank()) { "Season.label must not be blank" }
        if (endedAt != null) {
            require(!endedAt.isBefore(startedAt)) { "Season.endedAt must not precede startedAt" }
        }
    }

    public val isActive: Boolean get() = endedAt == null
}
