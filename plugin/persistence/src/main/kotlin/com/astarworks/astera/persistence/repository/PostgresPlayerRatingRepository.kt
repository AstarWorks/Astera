package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.rating.IPlayerRatingRepository
import com.astarworks.astera.match.domain.rating.Rating
import com.astarworks.astera.persistence.table.PlayerRatingsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerRatingRepository] (Wave 7b, ADR-0037).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * in-memory default in `plugin:match` (priority 0). The bounded
 * [Rating] value class is stored as a plain int — `Rating.of` clamps on read
 * so a manually-edited out-of-range row still decodes into a valid domain value.
 */
public class PostgresPlayerRatingRepository(private val db: Database) : IPlayerRatingRepository {

    override suspend fun find(playerId: PlayerId): Rating? = ioTransaction(db) {
        PlayerRatingsTable
            .selectAll()
            .where { PlayerRatingsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { Rating.of(it[PlayerRatingsTable.rating]) }
    }

    override suspend fun upsert(playerId: PlayerId, rating: Rating) {
        ioTransaction(db) {
            PlayerRatingsTable.upsert(PlayerRatingsTable.playerId) {
                it[PlayerRatingsTable.playerId] = playerId.uuid
                it[PlayerRatingsTable.rating] = rating.value
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun all(): Map<PlayerId, Rating> = ioTransaction(db) {
        PlayerRatingsTable
            .selectAll()
            .associate { row ->
                PlayerId(row[PlayerRatingsTable.playerId]) to Rating.of(row[PlayerRatingsTable.rating])
            }
    }
}
