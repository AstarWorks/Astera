package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayId
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository
import com.astarworks.astera.match.application.replay.json.KeyFramesJson
import com.astarworks.astera.match.application.replay.json.ReplayEventsJson
import com.astarworks.astera.match.application.replay.json.ReplayFormatJson
import com.astarworks.astera.match.application.replay.json.assembleRecordedReplay
import com.astarworks.astera.persistence.table.MatchReplaysTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory

/**
 * Postgres-backed [IMatchReplayRepository] (Wave 5v / ADR-0088).
 *
 * Registered by `AsteraPersistencePlugin` at priority 100; overlays the
 * `InMemoryMatchReplayRepository` registered by `AsteraMatchPlugin` at
 * priority 0. Upsert on `match_id` (latest-write-wins); re-recording the
 * same match mints a fresh `replay_id`.
 *
 * `save` errors are wrapped in [ReplayError.Storage] rather than rethrown
 * (Astera invariant: application API surface returns `Result<T, E>` over a
 * sealed error hierarchy, no raw JVM throwables — Konsist-enforced).
 */
public class PostgresMatchReplayRepository(private val db: Database) :
    IMatchReplayRepository {

    private val logger = LoggerFactory.getLogger(PostgresMatchReplayRepository::class.java)

    override suspend fun save(replay: RecordedReplay): Result<Unit, ReplayError> = runCatching {
        ioTransaction(db) {
            MatchReplaysTable.upsert(MatchReplaysTable.matchId) {
                it[matchId] = replay.matchId.value
                it[replayId] = replay.id.uuid
                it[startTick] = replay.startTick
                it[endTick] = replay.endTick
                it[sizeBytes] = replay.sizeBytes
                it[format] = ReplayFormatJson.fromDomain(replay.format)
                it[keyFrames] = KeyFramesJson.fromDomain(replay.keyFrames)
                it[events] = ReplayEventsJson.fromDomain(replay.events)
            }
        }
        Result.success(Unit)
    }.getOrElse { t ->
        logger.error("PostgresMatchReplayRepository.save(match=${replay.matchId}) failed", t)
        Result.failure(ReplayError.Storage(t.message ?: t::class.simpleName.orEmpty()))
    }

    override suspend fun findByMatchId(matchId: MatchId): RecordedReplay? = ioTransaction(db) {
        MatchReplaysTable
            .selectAll()
            .where { MatchReplaysTable.matchId eq matchId.value }
            .firstOrNull()
            ?.let { row ->
                assembleRecordedReplay(
                    replayId = ReplayId(row[MatchReplaysTable.replayId]),
                    matchId = MatchId(row[MatchReplaysTable.matchId]),
                    startTick = row[MatchReplaysTable.startTick],
                    endTick = row[MatchReplaysTable.endTick],
                    sizeBytes = row[MatchReplaysTable.sizeBytes],
                    formatJson = row[MatchReplaysTable.format],
                    keyFramesJson = row[MatchReplaysTable.keyFrames],
                    eventsJson = row[MatchReplaysTable.events],
                )
            }
    }

    override suspend fun findById(id: ReplayId): RecordedReplay? = ioTransaction(db) {
        MatchReplaysTable
            .selectAll()
            .where { MatchReplaysTable.replayId eq id.uuid }
            .firstOrNull()
            ?.let { row ->
                assembleRecordedReplay(
                    replayId = ReplayId(row[MatchReplaysTable.replayId]),
                    matchId = MatchId(row[MatchReplaysTable.matchId]),
                    startTick = row[MatchReplaysTable.startTick],
                    endTick = row[MatchReplaysTable.endTick],
                    sizeBytes = row[MatchReplaysTable.sizeBytes],
                    formatJson = row[MatchReplaysTable.format],
                    keyFramesJson = row[MatchReplaysTable.keyFrames],
                    eventsJson = row[MatchReplaysTable.events],
                )
            }
    }

    override suspend fun delete(matchId: MatchId) {
        ioTransaction(db) {
            MatchReplaysTable.deleteWhere {
                MatchReplaysTable.matchId eq matchId.value
            }
        }
    }
}
