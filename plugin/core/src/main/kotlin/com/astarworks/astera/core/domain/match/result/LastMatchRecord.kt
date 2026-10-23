package com.astarworks.astera.core.domain.match.result

/**
 * A player's most recent match, kept so the lobby can show it after the
 * transfer back cut the result screen short.
 *
 * @property teams `team:score` pairs in finishing order, e.g. `red:3,blue:5`.
 */
public data class LastMatchRecord(
    val matchId: String,
    val endedAtEpochSec: Long,
    val modeKey: String,
    val outcome: MatchOutcome,
    val teams: List<Pair<String, Int>>,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val objective: Int,
    val mvp: Boolean,
    val gold: Long,
    val xp: Long,
    val level: Int,
    val xpIntoLevel: Long,
    val xpForNextLevel: Long,
)
