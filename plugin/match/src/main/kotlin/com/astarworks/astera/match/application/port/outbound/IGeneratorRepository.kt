package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.generator.GeneratorId
import com.astarworks.astera.match.domain.generator.StarGenerator

/**
 * Outbound port: lookup / mutation surface for Star Generator instances
 * (Wave 5c).
 *
 * Why a feature-local repository rather than the generic
 * [com.astarworks.astera.core.application.port.outbound.Repository]: most
 * read sites need "every generator belonging to *this* match" — phrased as a
 * dedicated method here so callers do not loop+filter every tick. The default
 * in-memory impl
 * ([com.astarworks.astera.match.application.service.InMemoryGeneratorRepository])
 * holds the canonical state; the Persistence plugin (Wave 5e+) registers a
 * Postgres-backed override at higher priority.
 *
 * Single-threaded by contract — populated at match start, mutated on the
 * server main thread.
 */
public interface IGeneratorRepository {
    /** Every generator placed inside [matchId]. Returns an empty list if none. */
    public fun listForMatch(matchId: MatchId): List<StarGenerator>

    /** Insert or replace by [StarGenerator.id]. */
    public fun save(generator: StarGenerator)

    /** Drop the generator with [id], if present. No-op otherwise. */
    public fun remove(id: GeneratorId)

    /** Lookup by id; null if unknown. */
    public fun findById(id: GeneratorId): StarGenerator?
}
