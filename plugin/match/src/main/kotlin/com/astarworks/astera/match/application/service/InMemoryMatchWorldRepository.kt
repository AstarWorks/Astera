package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IMatchWorldRepository
import com.astarworks.astera.match.domain.match.MatchWorld

/**
 * Default in-memory [IMatchWorldRepository] (Wave 5d).
 *
 * Lives in production scope so [AsteraMatchPlugin][com.astarworks.astera.match.platform]
 * can wire it up directly at startup; a persistence-backed override (when
 * cross-restart match-world survival is required) would register at higher
 * priority in the Persistence plugin.
 *
 * **Not thread-safe** — Astera invariant: all match-state mutation runs on
 * the server main thread (per `IScheduler` regional contract). The plain
 * `MutableMap` is intentional; concurrent collections would imply support we
 * do not provide.
 */
public class InMemoryMatchWorldRepository : IMatchWorldRepository {

    private val store: MutableMap<MatchId, MatchWorld> = mutableMapOf()

    override fun findByMatch(matchId: MatchId): MatchWorld? = store[matchId]

    override fun save(matchWorld: MatchWorld) {
        store[matchWorld.matchId] = matchWorld
    }

    override fun remove(matchId: MatchId) {
        store.remove(matchId)
    }

    override fun all(): List<MatchWorld> = store.values.toList()
}
