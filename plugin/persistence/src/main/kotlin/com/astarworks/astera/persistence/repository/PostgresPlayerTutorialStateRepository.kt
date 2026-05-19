package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.PlayerTutorialState
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.persistence.jsonb.TutorialIdsJson
import com.astarworks.astera.persistence.table.PlayerTutorialStateTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

/**
 * Postgres-backed [IPlayerTutorialStateRepository] (Wave 5h, ADR-0074 §3).
 *
 * Registered by `AsteraPersistencePlugin` at priority=100; overlays the
 * in-memory default the core plugin registers. The two id sets are encoded as
 * JSONB via [TutorialIdsJson]; the cursor (`current_tutorial` / `current_step`)
 * lives in plain columns for cheap admin querying. See ADR-0014 for the
 * columnar-vs-JSONB rule of thumb (a bounded read-whole aggregate is JSONB
 * territory; nullable scalar cursor fields stay columnar).
 *
 * `upsert` writes the full aggregate on every transition (start / advance /
 * skip / replay / complete); concurrent writers race on `updated_at` only,
 * since the document is replaced wholesale.
 */
public class PostgresPlayerTutorialStateRepository(
    private val db: Database,
) : IPlayerTutorialStateRepository {

    override fun find(playerId: PlayerId): PlayerTutorialState? = transaction(db) {
        PlayerTutorialStateTable
            .selectAll()
            .where { PlayerTutorialStateTable.playerId eq playerId.uuid }
            .firstOrNull()
            ?.let { row ->
                PlayerTutorialState(
                    playerId = playerId,
                    completedTutorialScriptIds = row[PlayerTutorialStateTable.completedTutorials].toDomain(),
                    skippedTutorialScriptIds = row[PlayerTutorialStateTable.skippedTutorials].toDomain(),
                    currentTutorial = row[PlayerTutorialStateTable.currentTutorial]?.let(::TutorialScriptId),
                    currentStepIndex = row[PlayerTutorialStateTable.currentStep],
                    updatedAt = row[PlayerTutorialStateTable.updatedAt],
                )
            }
    }

    override fun upsert(state: PlayerTutorialState) {
        transaction(db) {
            PlayerTutorialStateTable.upsert(PlayerTutorialStateTable.playerId) {
                it[playerId] = state.playerId.uuid
                it[completedTutorials] = TutorialIdsJson.fromDomain(state.completedTutorialScriptIds)
                it[skippedTutorials] = TutorialIdsJson.fromDomain(state.skippedTutorialScriptIds)
                it[currentTutorial] = state.currentTutorial?.value
                it[currentStep] = state.currentStepIndex
                it[updatedAt] = Instant.now()
            }
        }
    }

    override fun delete(playerId: PlayerId) {
        transaction(db) {
            PlayerTutorialStateTable.deleteWhere { PlayerTutorialStateTable.playerId eq playerId.uuid }
        }
    }
}
