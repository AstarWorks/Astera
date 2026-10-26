package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.NameplateMilestonesTable
import com.astarworks.astera.stats.application.port.outbound.INameplateMilestoneRepository
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [INameplateMilestoneRepository]
 * (Wave 5flex / ADR-0102 surface #5 — V36).
 *
 * Registered at priority 100 by `AsteraPersistencePlugin`. Overlays
 * the in-memory default `InMemoryNameplateMilestoneRepository`
 * registered by `AsteraStatsPlugin`.
 */
public class PostgresNameplateMilestoneRepository(private val db: Database) : INameplateMilestoneRepository {

    override suspend fun find(playerId: PlayerId): INameplateMilestoneRepository.Record? = ioTransaction(db) {
        NameplateMilestonesTable
            .selectAll()
            .where { NameplateMilestonesTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.toDomain()
    }

    override suspend fun save(record: INameplateMilestoneRepository.Record): Unit = ioTransaction(db) {
        NameplateMilestonesTable.upsert(NameplateMilestonesTable.playerId) {
            it[playerId] = record.playerId.uuid
            it[achievementId] = record.achievementId?.value
            it[milestoneKey] = record.milestoneKey?.value
            it[setAt] = record.setAt
        }
        Unit
    }

    override suspend fun delete(playerId: PlayerId): Unit = ioTransaction(db) {
        NameplateMilestonesTable.deleteWhere { NameplateMilestonesTable.playerId eq playerId.uuid }
        Unit
    }

    private fun ResultRow.toDomain(): INameplateMilestoneRepository.Record {
        val achievement = this[NameplateMilestonesTable.achievementId]?.let { AchievementId(it) }
        val key = this[NameplateMilestonesTable.milestoneKey]?.let { MessageKey(it) }
        return INameplateMilestoneRepository.Record(
            playerId = PlayerId(this[NameplateMilestonesTable.playerId]),
            achievementId = achievement,
            milestoneKey = key,
            setAt = this[NameplateMilestonesTable.setAt],
        )
    }
}
