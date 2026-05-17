package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IStageRepository
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.persistence.jsonb.StageRegionsJson
import com.astarworks.astera.persistence.table.StagesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

/**
 * Postgres-backed [IStageRepository]. Registered by AsteraPersistencePlugin
 * at priority=100, overlays the InMemory default registered by feature
 * plugins (Phase 1 / Phase 3 stage authoring lives elsewhere).
 *
 * `regions` is stored as JSONB via [StageRegionsJson] — see ADR-0014.
 */
public class PostgresStageRepository(private val db: Database) : IStageRepository {

    override fun findById(id: StageId): Stage? = transaction(db) {
        StagesTable
            .selectAll()
            .where { StagesTable.id eq id.value }
            .firstOrNull()
            ?.toDomain()
    }

    override fun all(): Collection<Stage> = transaction(db) {
        StagesTable.selectAll().map { it.toDomain() }
    }

    override fun save(entity: Stage): Unit = transaction(db) {
        StagesTable.upsert(StagesTable.id) {
            it[id] = entity.id.value
            it[displayNameKey] = entity.displayNameKey.value
            it[regions] = StageRegionsJson.fromDomain(entity.regions)
        }
        Unit
    }

    override fun delete(id: StageId): Unit = transaction(db) {
        StagesTable.deleteWhere { StagesTable.id eq id.value }
        Unit
    }

    private fun ResultRow.toDomain(): Stage = Stage(
        id = StageId(this[StagesTable.id]),
        displayNameKey = MessageKey(this[StagesTable.displayNameKey]),
        regions = this[StagesTable.regions].toDomain(),
    )
}
