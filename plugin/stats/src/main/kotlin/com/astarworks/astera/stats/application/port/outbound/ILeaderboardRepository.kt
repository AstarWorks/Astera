package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.leaderboard.LeaderboardEntry
import com.astarworks.astera.stats.domain.season.SeasonId

/**
 * Outbound port for the per-season competitive leaderboard (Wave 7b, ADR-0037).
 *
 * In-memory default (priority 0) in `plugin:stats`; Postgres overlay (priority
 * 100, V48 `leaderboard_rankings`) in `plugin:persistence`. Entries are keyed
 * by ([SeasonId], [PlayerId]); a new season starts with an empty ranking.
 */
public interface ILeaderboardRepository {

    /**
     * Record a rating + result for [playerId] in [seasonId]. Replaces the
     * stored rating and increments the win or loss tally by one. Idempotency is
     * NOT assumed — each call counts as one game played.
     */
    public suspend fun apply(seasonId: SeasonId, playerId: PlayerId, rating: Int, won: Boolean)

    /** Top [n] entries for [seasonId], highest rating first. */
    public suspend fun topN(seasonId: SeasonId, n: Int): List<LeaderboardEntry>

    /** 1-based rank of [playerId] within [seasonId], or null if absent. */
    public suspend fun rankOf(seasonId: SeasonId, playerId: PlayerId): Int?

    /** The full entry for [playerId] in [seasonId], or null if absent. */
    public suspend fun entry(seasonId: SeasonId, playerId: PlayerId): LeaderboardEntry?
}
