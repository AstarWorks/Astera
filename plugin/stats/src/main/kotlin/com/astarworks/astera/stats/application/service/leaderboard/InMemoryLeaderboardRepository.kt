package com.astarworks.astera.stats.application.service.leaderboard

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository
import com.astarworks.astera.stats.domain.leaderboard.LeaderboardEntry
import com.astarworks.astera.stats.domain.season.SeasonId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ILeaderboardRepository] default (priority 0, Wave 7b, ADR-0037).
 *
 * Standings are partitioned by [SeasonId] so a season reset starts a fresh
 * ranking without touching prior history. The Postgres overlay in
 * `plugin:persistence` (priority 100) supersedes this at runtime.
 *
 * Ordering is rating-first, then [LeaderboardEntry.winRate] desc, then
 * [LeaderboardEntry.gamesPlayed] desc — so among equally-rated players the more
 * consistent winner ranks higher, and ties break toward the more-tested player.
 */
public class InMemoryLeaderboardRepository : ILeaderboardRepository {

    private val store: ConcurrentHashMap<SeasonId, ConcurrentHashMap<PlayerId, LeaderboardEntry>> =
        ConcurrentHashMap()

    private val ranking: Comparator<LeaderboardEntry> =
        compareByDescending<LeaderboardEntry> { it.rating }
            .thenByDescending { it.winRate }
            .thenByDescending { it.gamesPlayed }

    override fun apply(seasonId: SeasonId, playerId: PlayerId, rating: Int, won: Boolean) {
        val season = store.getOrPut(seasonId) { ConcurrentHashMap() }
        season.compute(playerId) { _, existing ->
            val wins = (existing?.wins ?: 0L) + if (won) 1L else 0L
            val losses = (existing?.losses ?: 0L) + if (won) 0L else 1L
            LeaderboardEntry(playerId = playerId, rating = rating, wins = wins, losses = losses)
        }
    }

    override fun topN(seasonId: SeasonId, n: Int): List<LeaderboardEntry> {
        if (n <= 0) return emptyList()
        val season = store[seasonId] ?: return emptyList()
        return season.values.sortedWith(ranking).take(n)
    }

    override fun rankOf(seasonId: SeasonId, playerId: PlayerId): Int? {
        val season = store[seasonId] ?: return null
        val ordered = season.values.sortedWith(ranking)
        val index = ordered.indexOfFirst { it.playerId == playerId }
        return if (index < 0) null else index + 1
    }

    override fun entry(seasonId: SeasonId, playerId: PlayerId): LeaderboardEntry? =
        store[seasonId]?.get(playerId)
}
