package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.persistence.table.PlayerStatsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerStatsRepository]. Registered by AsteraPersistencePlugin
 * at priority=100, overlays the InMemory default registered by AsteraStatsPlugin.
 */
public class PostgresPlayerStatsRepository(private val db: Database) : IPlayerStatsRepository {

    override fun find(playerId: PlayerId): PlayerStats? = transaction(db) {
        PlayerStatsTable
            .selectAll()
            .where { PlayerStatsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.toDomain()
    }

    override fun upsert(stats: PlayerStats): PlayerStats = transaction(db) {
        PlayerStatsTable.upsert(PlayerStatsTable.playerId) {
            it[playerId] = stats.playerId.uuid
            it[kills] = stats.kills
            it[deaths] = stats.deaths
            it[assists] = stats.assists
            it[damageDealt] = stats.damageDealt
            it[damageTaken] = stats.damageTaken
            it[playtimeSec] = stats.playtimeSec
            it[matchesPlayed] = stats.matchesPlayed
            it[wins] = stats.wins
            it[losses] = stats.losses
            it[updatedAt] = Instant.now()
        }
        stats
    }

    override fun all(): Collection<PlayerStats> = transaction(db) {
        PlayerStatsTable.selectAll().map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): PlayerStats = PlayerStats(
        playerId = PlayerId(this[PlayerStatsTable.playerId]),
        kills = this[PlayerStatsTable.kills],
        deaths = this[PlayerStatsTable.deaths],
        assists = this[PlayerStatsTable.assists],
        damageDealt = this[PlayerStatsTable.damageDealt],
        damageTaken = this[PlayerStatsTable.damageTaken],
        playtimeSec = this[PlayerStatsTable.playtimeSec],
        matchesPlayed = this[PlayerStatsTable.matchesPlayed],
        wins = this[PlayerStatsTable.wins],
        losses = this[PlayerStatsTable.losses],
    )
}
