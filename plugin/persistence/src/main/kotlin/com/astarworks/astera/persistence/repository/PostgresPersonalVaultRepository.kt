package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPersonalVaultRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.vault.PersonalVault
import com.astarworks.astera.persistence.jsonb.PersonalVaultJson
import com.astarworks.astera.persistence.table.PersonalVaultsTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPersonalVaultRepository] (Wave 5e / RTM issue #3).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default the core plugin registers. `items` is serialized as
 * JSONB via [PersonalVaultJson] — see ADR-0014 for the columnar-vs-JSONB
 * rule of thumb (bounded read-whole aggregates → JSONB).
 *
 * `upsert` writes the full aggregate (the GUI flushes on close); concurrent
 * writers race on `updated_at` only, since the items array is replaced
 * wholesale. If a player has two clients open simultaneously the last
 * close wins — same semantics as Loadout (ADR-0027).
 */
public class PostgresPersonalVaultRepository(private val db: Database) : IPersonalVaultRepository {

    override suspend fun find(playerId: PlayerId): PersonalVault? = ioTransaction(db) {
        PersonalVaultsTable
            .selectAll()
            .where { PersonalVaultsTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                row[PersonalVaultsTable.items].toDomain(playerId)
            }
    }

    override suspend fun upsert(vault: PersonalVault) {
        ioTransaction(db) {
            PersonalVaultsTable.upsert(PersonalVaultsTable.playerId) {
                it[playerId] = vault.playerId.uuid
                it[items] = PersonalVaultJson.fromDomain(vault)
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun remove(playerId: PlayerId) {
        ioTransaction(db) {
            PersonalVaultsTable.deleteWhere { PersonalVaultsTable.playerId eq playerId.uuid }
        }
    }
}
