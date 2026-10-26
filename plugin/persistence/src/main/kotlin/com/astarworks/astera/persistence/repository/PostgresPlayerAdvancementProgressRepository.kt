package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.PlayerAdvancementProgressTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerAdvancementProgressRepository] (Wave 5p, ADR-0088).
 *
 * Mirrors [PostgresNormaProgressRepository]'s shape — Exposed v1 upsert
 * keyed on the composite primary key `(player_id, advancement_id)`. Registered
 * at `priority=100` in [com.astarworks.astera.persistence.platform.AsteraPersistencePlugin],
 * overlays the in-memory default installed by `AsteraCorePlugin`.
 *
 * `loadbefore` on the persistence plugin's `plugin.yml` ensures this overlay
 * is on the registry before any feature plugin's `onEnable` resolves the
 * port — same convention as every other persistence overlay.
 */
public class PostgresPlayerAdvancementProgressRepository(
    private val db: Database,
) : IPlayerAdvancementProgressRepository {

    override suspend fun find(playerId: PlayerId, advancementId: AdvancementId): AdvancementProgress? =
        ioTransaction(db) {
            PlayerAdvancementProgressTable
                .selectAll()
                .where {
                    (PlayerAdvancementProgressTable.playerId eq playerId.uuid) and
                        (PlayerAdvancementProgressTable.advancementId eq advancementId.value)
                }
                .firstOrNull()
                ?.toDomain()
        }

    override suspend fun upsert(
        playerId: PlayerId,
        advancementId: AdvancementId,
        progress: AdvancementProgress,
    ) {
        ioTransaction(db) {
            PlayerAdvancementProgressTable.upsert(
                PlayerAdvancementProgressTable.playerId,
                PlayerAdvancementProgressTable.advancementId,
            ) {
                it[PlayerAdvancementProgressTable.playerId] = playerId.uuid
                it[PlayerAdvancementProgressTable.advancementId] = advancementId.value
                it[currentCount] = progress.current
                it[targetCount] = progress.target
                it[completedAt] = progress.completedAt
                it[updatedAt] = Instant.now()
            }
        }
    }

    override suspend fun allFor(playerId: PlayerId): Map<AdvancementId, AdvancementProgress> =
        ioTransaction(db) {
            PlayerAdvancementProgressTable
                .selectAll()
                .where { PlayerAdvancementProgressTable.playerId eq playerId.uuid }
                .associate {
                    AdvancementId(it[PlayerAdvancementProgressTable.advancementId]) to it.toDomain()
                }
        }

    private fun ResultRow.toDomain(): AdvancementProgress = AdvancementProgress(
        current = this[PlayerAdvancementProgressTable.currentCount],
        target = this[PlayerAdvancementProgressTable.targetCount],
        completedAt = this[PlayerAdvancementProgressTable.completedAt],
    )
}
