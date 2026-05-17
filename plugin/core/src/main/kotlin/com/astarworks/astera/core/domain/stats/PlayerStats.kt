package com.astarworks.astera.core.domain.stats

import com.astarworks.astera.core.domain.player.PlayerId

public data class PlayerStats(
    val playerId: PlayerId,
    val kills: Long,
    val deaths: Long,
    val assists: Long,
    val damageDealt: Long,
    val damageTaken: Long,
    val playtimeSec: Long,
    val matchesPlayed: Long,
    val wins: Long = 0L,
    val losses: Long = 0L,
) {
    val kdRatio: Double
        get() = if (deaths == 0L) kills.toDouble() else kills.toDouble() / deaths

    public companion object {
        public fun empty(playerId: PlayerId): PlayerStats =
            PlayerStats(playerId, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
    }
}
