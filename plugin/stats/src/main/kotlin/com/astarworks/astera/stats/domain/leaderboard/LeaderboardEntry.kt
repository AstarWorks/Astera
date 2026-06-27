package com.astarworks.astera.stats.domain.leaderboard

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * One player's standing on a season leaderboard (Wave 7b, ADR-0037).
 *
 * Materialised in Stats from [com.astarworks.astera.core.domain.event.PlayerRatingUpdated]
 * events (rating snapshot + W/L tally). `rating` is a plain Int — the Match
 * `Rating` value class does not cross the plugin boundary (ADR-0022).
 */
public data class LeaderboardEntry(
    val playerId: PlayerId,
    val rating: Int,
    val wins: Long,
    val losses: Long,
) {
    init {
        require(wins >= 0) { "LeaderboardEntry.wins must be >= 0" }
        require(losses >= 0) { "LeaderboardEntry.losses must be >= 0" }
    }

    public val gamesPlayed: Long get() = wins + losses

    public val winRate: Double
        get() = if (gamesPlayed == 0L) 0.0 else wins.toDouble() / gamesPlayed
}
