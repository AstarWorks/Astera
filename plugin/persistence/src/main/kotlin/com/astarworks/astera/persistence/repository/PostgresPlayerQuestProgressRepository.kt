package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStageId
import com.astarworks.astera.persistence.table.PlayerQuestProgressTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerQuestProgressRepository] (Wave 5s, ADR-0091).
 *
 * Mirrors [PostgresPlayerAdvancementProgressRepository]'s shape — Exposed v1
 * upsert keyed on the composite primary key `(player_id, chain_id)`. Registered
 * at `priority=100` in [com.astarworks.astera.persistence.platform.AsteraPersistencePlugin],
 * overlays the in-memory default installed by `AsteraCorePlugin`.
 */
public class PostgresPlayerQuestProgressRepository(
    private val db: Database,
) : IPlayerQuestProgressRepository {

    override fun find(playerId: PlayerId, chainId: QuestChainId): PlayerQuestProgress? =
        transaction(db) {
            PlayerQuestProgressTable
                .selectAll()
                .where {
                    (PlayerQuestProgressTable.playerId eq playerId.uuid) and
                        (PlayerQuestProgressTable.chainId eq chainId.value)
                }
                .firstOrNull()
                ?.toDomain(playerId)
        }

    override fun upsert(progress: PlayerQuestProgress) {
        transaction(db) {
            PlayerQuestProgressTable.upsert(
                PlayerQuestProgressTable.playerId,
                PlayerQuestProgressTable.chainId,
            ) {
                it[PlayerQuestProgressTable.playerId] = progress.playerId.uuid
                it[PlayerQuestProgressTable.chainId] = progress.chainId.value
                it[currentStageId] = progress.currentStageId.value
                it[stageProgress] = progress.stageProgress
                it[completedAt] = progress.completedAt
                it[updatedAt] = Instant.now()
            }
        }
    }

    override fun allFor(playerId: PlayerId): Collection<PlayerQuestProgress> =
        transaction(db) {
            PlayerQuestProgressTable
                .selectAll()
                .where { PlayerQuestProgressTable.playerId eq playerId.uuid }
                .map { it.toDomain(playerId) }
        }

    private fun ResultRow.toDomain(playerId: PlayerId): PlayerQuestProgress = PlayerQuestProgress(
        playerId = playerId,
        chainId = QuestChainId(this[PlayerQuestProgressTable.chainId]),
        currentStageId = QuestStageId(this[PlayerQuestProgressTable.currentStageId]),
        stageProgress = this[PlayerQuestProgressTable.stageProgress],
        completedAt = this[PlayerQuestProgressTable.completedAt],
        updatedAt = this[PlayerQuestProgressTable.updatedAt],
    )
}
