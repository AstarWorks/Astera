package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IGeneratorRepository
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.StarGenerator

/**
 * Default in-memory [IGeneratorRepository] (Wave 5c).
 *
 * Lives in production scope so feature plugins can wire it up directly at
 * startup; the Persistence plugin (Wave 5e+) registers a Postgres-backed
 * override with higher priority.
 *
 * **Not thread-safe** — Astera invariant: all match-state mutation runs on
 * the server main thread (per `IScheduler` regional contract). The plain
 * `MutableMap` is intentional; concurrent collections would imply support we
 * do not provide.
 */
public class InMemoryGeneratorRepository : IGeneratorRepository {

    private val store: MutableMap<GeneratorId, StarGenerator> = mutableMapOf()

    override fun listForMatch(matchId: MatchId): List<StarGenerator> =
        store.values.filter { it.matchId == matchId }

    override fun save(generator: StarGenerator) {
        store[generator.id] = generator
    }

    override fun remove(id: GeneratorId) {
        store.remove(id)
    }

    override fun findById(id: GeneratorId): StarGenerator? = store[id]
}
