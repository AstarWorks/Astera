package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.application.port.outbound.IPlayerTutorialStateRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.persistence.table.PlayerTutorialStateTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert

public class PostgresPlayerTutorialStateRepository(private val db: Database) : IPlayerTutorialStateRepository {
    override fun find(playerId: PlayerId): TutorialRunnerSnapshot? = transaction(db) {
        PlayerTutorialStateTable.selectAll().where { PlayerTutorialStateTable.playerId eq playerId.uuid }
            .firstOrNull()?.let(::snapshot)
    }

    override fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot) {
        transaction(db) {
            PlayerTutorialStateTable.upsert(PlayerTutorialStateTable.playerId) {
                it[PlayerTutorialStateTable.playerId] = playerId.uuid
                it[scriptId] = snapshot.scriptId.value
                it[stepIndex] = snapshot.stepIndex
                it[startedAtTick] = snapshot.startedAtTick
                it[paused] = snapshot.paused
                it[completed] = snapshot.completed
                it[rewardGranted] = snapshot.rewardGranted
            }
        }
    }

    override fun clear(playerId: PlayerId) {
        transaction(db) { PlayerTutorialStateTable.deleteWhere { PlayerTutorialStateTable.playerId eq playerId.uuid } }
    }

    override fun all(): Map<PlayerId, TutorialRunnerSnapshot> = transaction(db) {
        PlayerTutorialStateTable.selectAll().associate { PlayerId(it[PlayerTutorialStateTable.playerId]) to snapshot(it) }
    }

    private fun snapshot(row: ResultRow): TutorialRunnerSnapshot = with(PlayerTutorialStateTable) {
        TutorialRunnerSnapshot(TutorialScriptId(row[scriptId]), row[stepIndex], row[startedAtTick],
            row[paused], row[completed], row[rewardGranted])
    }
}
