package com.astarworks.astera.match.application.service.replay

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayId
import com.astarworks.astera.match.application.port.outbound.IMatchReplayRepository

/**
 * Postgres-less default for [IMatchReplayRepository] (Wave 5v, ADR-0094).
 *
 * Registered by `AsteraMatchPlugin` at priority 0; `AsteraPersistencePlugin`
 * overlays a `PostgresMatchReplayRepository` at priority 100 when env vars
 * are present. Stores one row per `matchId` (latest-write-wins on `save`),
 * mirroring the Postgres semantics.
 *
 * **Not thread-safe** — same invariant as other in-memory repositories
 * (`InMemoryMatchRepository`, `InMemoryGeneratorRepository`): mutation
 * happens on the server main thread (`IScheduler` regional contract).
 */
public class InMemoryMatchReplayRepository : IMatchReplayRepository {

    private val byMatch: MutableMap<MatchId, RecordedReplay> = mutableMapOf()
    private val byReplay: MutableMap<ReplayId, RecordedReplay> = mutableMapOf()

    override suspend fun save(replay: RecordedReplay): Result<Unit, ReplayError> {
        // Drop any prior row's secondary index entry so findById on an old id
        // returns null after a re-record (latest-write-wins semantics).
        byMatch[replay.matchId]?.let { byReplay.remove(it.id) }
        byMatch[replay.matchId] = replay
        byReplay[replay.id] = replay
        return Result.success(Unit)
    }

    override suspend fun findByMatchId(matchId: MatchId): RecordedReplay? = byMatch[matchId]

    override suspend fun findById(id: ReplayId): RecordedReplay? = byReplay[id]

    override suspend fun delete(matchId: MatchId) {
        byMatch.remove(matchId)?.let { byReplay.remove(it.id) }
    }
}
