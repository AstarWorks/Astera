package com.astarworks.astera.persistence.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import java.time.Instant

/**
 * `leaderboard_rankings` table — per-season competitive standings (Wave 7b,
 * ADR-0037, V48 migration).
 *
 * Mirrors [com.astarworks.astera.stats.domain.leaderboard.LeaderboardEntry],
 * partitioned by [com.astarworks.astera.stats.domain.season.SeasonId]. Composite
 * PK on `(season_id, player_id)`: a new season starts with an empty ranking
 * without deleting prior history. The `(season_id, rating DESC)` index (V48)
 * backs topN / rankOf.
 */
public object LeaderboardRankingsTable : Table("leaderboard_rankings") {
    public val seasonId = varchar("season_id", 64)
    public val playerId = javaUUID("player_id")
    public val rating = integer("rating")
    public val wins = long("wins").default(0L)
    public val losses = long("losses").default(0L)
    public val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }
    override val primaryKey: PrimaryKey = PrimaryKey(seasonId, playerId)
}
