package com.astarworks.astera.match.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.application.port.outbound.IAnomalyScheduleRepository
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor

/**
 * Default in-memory [IAnomalyScheduleRepository] (Wave 5c).
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
public class InMemoryAnomalyScheduleRepository : IAnomalyScheduleRepository {

    private val store: MutableMap<MatchId, List<AnomalyDescriptor>> = mutableMapOf()

    override fun forMatch(matchId: MatchId): List<AnomalyDescriptor> =
        store[matchId].orEmpty()

    override fun setForMatch(matchId: MatchId, descriptors: List<AnomalyDescriptor>) {
        store[matchId] = descriptors.toList()
    }
}
