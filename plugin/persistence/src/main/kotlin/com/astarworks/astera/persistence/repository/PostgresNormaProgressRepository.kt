package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.NormaProgressTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [INormaProgressRepository].
 *
 * Wave 4.5 (R3.5) / ADR-0020 / ADR-0027: registered by
 * `AsteraPersistencePlugin` at `priority=100`, overlays the InMemory default
 * that `AsteraStatsPlugin` registers at the default priority. `loadbefore`
 * on the Persistence plugin's `plugin.yml` ensures the Postgres impl is on
 * the registry before any feature plugin looks it up.
 *
 * Mirrors [PostgresPlayerStatsRepository] (same Exposed v1 + upsert DSL
 * shape).
 */
public class PostgresNormaProgressRepository(private val db: Database) : INormaProgressRepository {

    override suspend fun find(playerId: PlayerId, normaId: NormaId): MissionProgress? = ioTransaction(db) {
        NormaProgressTable
            .selectAll()
            .where {
                (NormaProgressTable.playerId eq playerId.uuid) and
                    (NormaProgressTable.normaId eq normaId.value)
            }
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun upsert(playerId: PlayerId, progress: MissionProgress) {
        // Defensive: the port contract requires playerId == progress.playerId.
        // We trust the call site (InMemoryNormaService) but store the explicit
        // playerId so a stray mismatch can't corrupt rows.
        ioTransaction(db) {
            NormaProgressTable.upsert(NormaProgressTable.playerId, NormaProgressTable.normaId) {
                it[NormaProgressTable.playerId] = playerId.uuid
                it[NormaProgressTable.normaId] = progress.normaId.value
                it[currentCount] = progress.current
                it[completed] = progress.completed
                it[completedAt] = progress.completedAt
                it[periodKey] = progress.periodKey
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun allFor(playerId: PlayerId): Collection<MissionProgress> = ioTransaction(db) {
        NormaProgressTable
            .selectAll()
            .where { NormaProgressTable.playerId eq playerId.uuid }
            .map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): MissionProgress = MissionProgress(
        playerId = PlayerId(this[NormaProgressTable.playerId]),
        normaId = NormaId(this[NormaProgressTable.normaId]),
        current = this[NormaProgressTable.currentCount],
        completed = this[NormaProgressTable.completed],
        completedAt = this[NormaProgressTable.completedAt],
        periodKey = this[NormaProgressTable.periodKey],
    )
}
