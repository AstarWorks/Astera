package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IPowerIndexRepository
import com.astarworks.astera.economy.domain.power.PowerIndex
import com.astarworks.astera.persistence.table.PowerIndexSnapshotsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IPowerIndexRepository] (Wave 5flex / ADR-0102
 * surface #4).
 *
 * Registered at priority 100 by `AsteraPersistencePlugin`; overlays the
 * in-memory default (`InMemoryPowerIndexRepository`) registered by
 * `AsteraEconomyPlugin` at priority 0, following the Wave 3 / ADR-0020
 * pattern.
 *
 * # Read characteristics
 *
 *   - [find] — PK lookup on `player_id`, O(1).
 *   - [topN] — covered by `idx_power_index_total` (V34); pushdown sort.
 *
 * # Write characteristics
 *
 *   - [save] — single upsert keyed on `player_id`. Materialises `total`
 *     so SQL-side ORDER BY needs no recomputation.
 */
public class PostgresPowerIndexRepository(private val db: Database) : IPowerIndexRepository {

    override suspend fun find(playerId: PlayerId): PowerIndex? = ioTransaction(db) {
        PowerIndexSnapshotsTable
            .selectAll()
            .where { PowerIndexSnapshotsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun save(snapshot: PowerIndex): Unit = ioTransaction(db) {
        PowerIndexSnapshotsTable.upsert(PowerIndexSnapshotsTable.playerId) {
            it[playerId] = snapshot.playerId.uuid
            it[equipmentValue] = snapshot.equipmentValue
            it[vaultValue] = snapshot.vaultValue
            it[walletValue] = snapshot.walletValue
            it[marketplaceListingValue] = snapshot.marketplaceListingValue
            it[total] = snapshot.total
            it[computedAt] = snapshot.computedAt
        }
        Unit
    }

    override suspend fun delete(playerId: PlayerId): Unit = ioTransaction(db) {
        PowerIndexSnapshotsTable.deleteWhere { PowerIndexSnapshotsTable.playerId eq playerId.uuid }
        Unit
    }

    override suspend fun topN(n: Int): List<PowerIndex> {
        if (n <= 0) return emptyList()
        return ioTransaction(db) {
            PowerIndexSnapshotsTable
                .selectAll()
                .orderBy(
                    PowerIndexSnapshotsTable.total to SortOrder.DESC,
                    PowerIndexSnapshotsTable.computedAt to SortOrder.DESC,
                )
                .limit(n)
                .map { it.toDomain() }
        }
    }

    private fun ResultRow.toDomain(): PowerIndex = PowerIndex(
        playerId = PlayerId(this[PowerIndexSnapshotsTable.playerId]),
        equipmentValue = this[PowerIndexSnapshotsTable.equipmentValue],
        vaultValue = this[PowerIndexSnapshotsTable.vaultValue],
        walletValue = this[PowerIndexSnapshotsTable.walletValue],
        marketplaceListingValue = this[PowerIndexSnapshotsTable.marketplaceListingValue],
        computedAt = this[PowerIndexSnapshotsTable.computedAt],
    )
}
