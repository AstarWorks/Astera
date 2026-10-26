package com.astarworks.astera.match.application.port.outbound.tournament

import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentId

/**
 * Outbound port for tournament persistence (Wave 7b, ADR-0037).
 *
 * In-memory default (`InMemoryTournamentRepository`, priority 0) ships in
 * `plugin:match`; `plugin:persistence` overlays a Postgres impl at priority
 * 100 (`PostgresTournamentRepository`, V46 `tournaments`, bracket stored as
 * JSONB). Plain CRUD — expected failure modes (not-found, illegal report) are
 * surfaced by the use cases as `Result<T, TournamentError>`, not here, mirroring
 * `IPlayerStatsRepository` / `IMatchmaker`'s split of concerns.
 */
public interface ITournamentRepository {

    /** The tournament with [id], or null if none. */
    public suspend fun find(id: TournamentId): Tournament?

    /** Insert or replace [tournament]; returns the stored value. */
    public suspend fun upsert(tournament: Tournament): Tournament

    /** All known tournaments (unordered). */
    public suspend fun all(): Collection<Tournament>

    /** Remove the tournament with [id]; no-op if absent. */
    public suspend fun delete(id: TournamentId)
}
