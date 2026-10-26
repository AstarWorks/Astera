package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.LeaderboardRankingsTable
import com.astarworks.astera.stats.application.port.outbound.ILeaderboardRepository
import com.astarworks.astera.stats.domain.leaderboard.LeaderboardEntry
import com.astarworks.astera.stats.domain.season.SeasonId
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [ILeaderboardRepository] (Wave 7b, ADR-0037).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * in-memory default in `plugin:stats` (priority 0). Entries are keyed by
 * `(seasonId, playerId)`; [topN] / [rankOf] are backed by the
 * `(season_id, rating DESC)` index (V48).
 */
public class PostgresLeaderboardRepository(private val db: Database) : ILeaderboardRepository {

    override suspend fun apply(seasonId: SeasonId, playerId: PlayerId, rating: Int, won: Boolean) {
        ioTransaction(db) {
            // Read-modify-write the tally: each call counts as one game played,
            // so the win/loss column grows by one off the current value.
            val current = entryRow(seasonId, playerId)
            val wins = (current?.get(LeaderboardRankingsTable.wins) ?: 0L) + if (won) 1L else 0L
            val losses = (current?.get(LeaderboardRankingsTable.losses) ?: 0L) + if (won) 0L else 1L

            LeaderboardRankingsTable.upsert(
                LeaderboardRankingsTable.seasonId,
                LeaderboardRankingsTable.playerId,
            ) {
                it[LeaderboardRankingsTable.seasonId] = seasonId.value
                it[LeaderboardRankingsTable.playerId] = playerId.uuid
                it[LeaderboardRankingsTable.rating] = rating
                it[LeaderboardRankingsTable.wins] = wins
                it[LeaderboardRankingsTable.losses] = losses
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun topN(seasonId: SeasonId, n: Int): List<LeaderboardEntry> {
        if (n <= 0) return emptyList()
        return ioTransaction(db) {
            LeaderboardRankingsTable
                .selectAll()
                .where { LeaderboardRankingsTable.seasonId eq seasonId.value }
                .orderBy(LeaderboardRankingsTable.rating to SortOrder.DESC)
                .limit(n)
                .map { it.toDomain() }
        }
    }

    override suspend fun rankOf(seasonId: SeasonId, playerId: PlayerId): Int? = ioTransaction(db) {
        val mine = entryRow(seasonId, playerId)?.get(LeaderboardRankingsTable.rating)
            ?: return@ioTransaction null
        val higher = LeaderboardRankingsTable
            .selectAll()
            .where {
                (LeaderboardRankingsTable.seasonId eq seasonId.value) and
                    (LeaderboardRankingsTable.rating greater mine)
            }
            .count()
        (higher + 1).toInt()
    }

    override suspend fun entry(seasonId: SeasonId, playerId: PlayerId): LeaderboardEntry? = ioTransaction(db) {
        entryRow(seasonId, playerId)?.toDomain()
    }

    private fun entryRow(seasonId: SeasonId, playerId: PlayerId): ResultRow? =
        LeaderboardRankingsTable
            .selectAll()
            .where {
                (LeaderboardRankingsTable.seasonId eq seasonId.value) and
                    (LeaderboardRankingsTable.playerId eq playerId.uuid)
            }
            .firstOrNull()

    private fun ResultRow.toDomain(): LeaderboardEntry = LeaderboardEntry(
        playerId = PlayerId(this[LeaderboardRankingsTable.playerId]),
        rating = this[LeaderboardRankingsTable.rating],
        wins = this[LeaderboardRankingsTable.wins],
        losses = this[LeaderboardRankingsTable.losses],
    )
}
