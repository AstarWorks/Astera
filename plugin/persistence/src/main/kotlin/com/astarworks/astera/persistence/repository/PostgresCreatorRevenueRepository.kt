package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue
import com.astarworks.astera.persistence.table.CreatorRevenueRecordsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [ICreatorRevenueRepository] (RTM #12, ADR-0035, Wave 5f).
 *
 * Registered by `AsteraPersistencePlugin` at `priority=100`, overlays the
 * in-memory default registered by `AsteraEconomyPlugin`. Single-row-per-item
 * upsert via [CreatorRevenueRecordsTable.itemId] (the PK), mirroring the
 * Exposed v1 + upsert DSL shape used by [PostgresNormaProgressRepository].
 */
public class PostgresCreatorRevenueRepository(private val db: Database) : ICreatorRevenueRepository {

    override suspend fun findByItem(itemId: ItemId): CreatorRevenue? = ioTransaction(db) {
        CreatorRevenueRecordsTable
            .selectAll()
            .where { CreatorRevenueRecordsTable.itemId eq itemId.value }
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun save(record: CreatorRevenue): Unit = ioTransaction(db) {
        CreatorRevenueRecordsTable.upsert(CreatorRevenueRecordsTable.itemId) {
            it[itemId] = record.itemId.value
            it[creatorId] = record.creatorId.uuid
            it[revenueShare] = record.revenueShare
        }
        Unit
    }

    override suspend fun delete(itemId: ItemId): Unit = ioTransaction(db) {
        CreatorRevenueRecordsTable.deleteWhere {
            CreatorRevenueRecordsTable.itemId eq itemId.value
        }
        Unit
    }

    override suspend fun all(): List<CreatorRevenue> = ioTransaction(db) {
        CreatorRevenueRecordsTable.selectAll().map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): CreatorRevenue = CreatorRevenue(
        itemId = ItemId(this[CreatorRevenueRecordsTable.itemId]),
        creatorId = PlayerId(this[CreatorRevenueRecordsTable.creatorId]),
        revenueShare = this[CreatorRevenueRecordsTable.revenueShare],
    )
}
