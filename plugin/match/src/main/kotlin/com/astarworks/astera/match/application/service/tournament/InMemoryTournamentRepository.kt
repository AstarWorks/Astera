package com.astarworks.astera.match.application.service.tournament

import com.astarworks.astera.match.application.port.outbound.tournament.ITournamentRepository
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ITournamentRepository] default (priority 0). Lives in production
 * scope so `plugin:match` boots stand-alone; `plugin:persistence` registers a
 * Postgres-backed override at higher priority (Wave 7b, ADR-0037).
 *
 * The active-tournament table is tiny (a handful per server), so the plain map
 * with no indexing is fine.
 */
public class InMemoryTournamentRepository : ITournamentRepository {

    private val store: MutableMap<TournamentId, Tournament> = ConcurrentHashMap()

    override fun find(id: TournamentId): Tournament? = store[id]

    override fun upsert(tournament: Tournament): Tournament {
        store[tournament.id] = tournament
        return tournament
    }

    override fun all(): Collection<Tournament> = store.values.toList()

    override fun delete(id: TournamentId) {
        store.remove(id)
    }
}
