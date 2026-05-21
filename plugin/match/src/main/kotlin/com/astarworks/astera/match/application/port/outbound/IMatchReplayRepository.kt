package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.replay.RecordedReplay
import com.astarworks.astera.core.domain.replay.ReplayError
import com.astarworks.astera.core.domain.replay.ReplayId

/**
 * Outbound port: storage for finalised [RecordedReplay] (Wave 5v, ADR-0088).
 *
 * Two implementations land in this wave: an in-memory map (registered at
 * priority 0 from `AsteraMatchPlugin`, used when Postgres env vars are
 * absent) and the Postgres-backed one (registered at priority 100 from
 * `AsteraPersistencePlugin`, V20 `match_replays` table; JSONB columns for
 * events + key frames per ADR-0014).
 *
 * `save` is a per-match upsert: latest-write-wins on `match_id` (one row
 * per match). Re-recording the same match overwrites; `id` (`ReplayId`)
 * is then a fresh UUID, but `match_id` stays primary lookup key. The
 * ReplayId is preserved on the row for forensic / audit chains and so a
 * future migration to append-only history can flip the PK without a
 * data-model change.
 */
public interface IMatchReplayRepository {

    /** Persist [replay], overwriting any prior recording for the same match. */
    public fun save(replay: RecordedReplay): Result<Unit, ReplayError>

    /** Load the most recent recording for [matchId], or null if none stored. */
    public fun findByMatchId(matchId: MatchId): RecordedReplay?

    /** Load by the row's own replay id. */
    public fun findById(id: ReplayId): RecordedReplay?

    /** Delete the row for [matchId]. No-op if absent. */
    public fun delete(matchId: MatchId)
}
