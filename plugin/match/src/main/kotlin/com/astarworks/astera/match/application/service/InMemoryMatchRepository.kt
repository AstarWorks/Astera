package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IMatchRepository] default. Lives in production scope so feature
 * plugins can wire it up directly at startup; the Persistence plugin
 * (Stage 4-改) registers a Postgres-backed override with higher priority.
 *
 * Findings such as `findActiveMatches` / `findMatchOf` are O(n); the active-
 * match table is small (one match per server in Phase 2), so the simple scan
 * is fine for now.
 */
public class InMemoryMatchRepository : IMatchRepository {

    private val store: MutableMap<MatchId, Match> = ConcurrentHashMap()

    override fun findById(id: MatchId): Match? = store[id]

    override fun all(): Collection<Match> = store.values.toList()

    override fun save(entity: Match) {
        store[entity.id] = entity
    }

    override fun delete(id: MatchId) {
        store.remove(id)
    }

    override fun findActiveMatches(): Collection<Match> =
        store.values.filter { it.phase != MatchPhase.ENDED }

    override fun findMatchOf(playerId: PlayerId): Match? =
        store.values.firstOrNull { match ->
            match.teams.any { team -> playerId in team.members }
        }

    override fun findInPhase(phase: MatchPhase): Collection<Match> =
        store.values.filter { it.phase == phase }
}
