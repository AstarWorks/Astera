package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IStageRegistry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.persistence.jsonb.StageRegionsJson
import com.astarworks.astera.persistence.table.StagesTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Postgres-backed [IStageRegistry] (Wave _infra-2b, ADR-0065). Registered by
 * `AsteraPersistencePlugin` at priority=100, overlays the [StageContents]
 * in-memory default registered by Astera-Match.
 *
 * Read-only by contract — stages are authored in `content/stages/` (YAML →
 * [StageContents.bootstrap]) and projected to Postgres by an out-of-band
 * sync (Phase 3). Mutation through this class would create a competing
 * source of truth, which the legacy `save` / `delete` surface accidentally
 * encouraged. The substrate's `Contents<StageId, Stage>` exposes only `find`
 * + `all`, matching the read-only intent.
 *
 * `regions` is stored as JSONB via [StageRegionsJson] — see ADR-0014.
 */
public class PostgresStageRegistry(private val db: Database) : IStageRegistry {

    override fun find(key: StageId): Stage? = transaction(db) {
        StagesTable
            .selectAll()
            .where { StagesTable.id eq key.value }
            .firstOrNull()
            ?.toDomain()
    }

    override fun all(): Collection<Stage> = transaction(db) {
        StagesTable.selectAll().map { it.toDomain() }
    }

    private fun ResultRow.toDomain(): Stage = Stage(
        id = StageId(this[StagesTable.id]),
        displayNameKey = MessageKey(this[StagesTable.displayNameKey]),
        regions = this[StagesTable.regions].toDomain(),
    )
}
