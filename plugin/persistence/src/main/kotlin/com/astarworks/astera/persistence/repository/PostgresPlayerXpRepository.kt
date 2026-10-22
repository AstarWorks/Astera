package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.PlayerXpTable
import com.astarworks.astera.stats.application.port.outbound.IPlayerXpRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/** Postgres-backed [IPlayerXpRepository] (V50, ADR-0187). `addXp` is an atomic `ON CONFLICT DO UPDATE total_xp = total_xp + n`. */
public class PostgresPlayerXpRepository(private val db: Database) : IPlayerXpRepository {

    override fun totalXp(playerId: PlayerId): Long = transaction(db) {
        PlayerXpTable.selectAll()
            .where { PlayerXpTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.get(PlayerXpTable.totalXp) ?: 0L
    }

    override fun addXp(playerId: PlayerId, amount: Long): Long = transaction(db) {
        PlayerXpTable.upsert(
            PlayerXpTable.playerId,
            onUpdate = {
                it[PlayerXpTable.totalXp] = PlayerXpTable.totalXp + amount
                it[PlayerXpTable.updatedAt] = Instant.now()
            },
        ) {
            it[PlayerXpTable.playerId] = playerId.uuid
            it[totalXp] = amount
            it[updatedAt] = Instant.now()
        }
        PlayerXpTable.selectAll()
            .where { PlayerXpTable.playerId eq playerId.uuid }
            .first()[PlayerXpTable.totalXp]
    }
}
