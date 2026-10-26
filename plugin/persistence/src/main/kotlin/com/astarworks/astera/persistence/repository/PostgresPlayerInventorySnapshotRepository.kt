package com.astarworks.astera.persistence.repository

import com.astarworks.astera.persistence.adapter.io.blockingTransaction
import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.persistence.jsonb.PlayerInventorySnapshotJson
import com.astarworks.astera.persistence.table.PlayerInventorySnapshotsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IPlayerInventorySnapshotRepository] (Wave 5e / RTM issue #5).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default the core plugin registers. The four canonical regions are
 * encoded into a single JSONB document via [PlayerInventorySnapshotJson]
 * (sealed `InventoryItemSlot` discriminator: `type: content | bukkit`).
 *
 * `save` is an upsert on `player_id`: latest-write-wins, no history (the
 * domain treats the snapshot as a single per-player slot). `version` +
 * `saved_at` live as columnar fields so admin queries (`SELECT player_id
 * FROM player_inventory_snapshots WHERE version < 1`) can filter without
 * parsing JSONB.
 *
 * Concurrent writers race on `saved_at` only; the items array is replaced
 * wholesale. Same semantics as Loadout (ADR-0027) and PersonalVault.
 */
public class PostgresPlayerInventorySnapshotRepository(private val db: Database) :
    IPlayerInventorySnapshotRepository {

    override fun save(snapshot: PlayerInventorySnapshot) {
        blockingTransaction(db) {
            PlayerInventorySnapshotsTable.upsert(PlayerInventorySnapshotsTable.playerId) {
                it[playerId] = snapshot.playerId.uuid
                it[PlayerInventorySnapshotsTable.snapshot] = PlayerInventorySnapshotJson.fromDomain(snapshot)
                it[version] = snapshot.version
                it[savedAt] = snapshot.savedAt
            }
        }
    }

    override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot? = blockingTransaction(db) {
        PlayerInventorySnapshotsTable
            .selectAll()
            .where { PlayerInventorySnapshotsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                row[PlayerInventorySnapshotsTable.snapshot].toDomain(
                    playerId = playerId,
                    version = row[PlayerInventorySnapshotsTable.version],
                    savedAt = row[PlayerInventorySnapshotsTable.savedAt],
                )
            }
    }

    override fun delete(playerId: PlayerId) {
        blockingTransaction(db) {
            PlayerInventorySnapshotsTable.deleteWhere {
                PlayerInventorySnapshotsTable.playerId eq playerId.uuid
            }
        }
    }
}
