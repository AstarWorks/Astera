package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.match.domain.anomaly.AnomalyDescriptor

/**
 * Outbound port: per-match registry of pre-authored [AnomalyDescriptor]s
 * (Wave 5c).
 *
 * Populated at match start (typically from `content/anomaly/<file>.yaml` via a
 * loader service); read by
 * [com.astarworks.astera.match.application.service.AnomalyScheduler] each
 * tick to sample which anomaly (if any) should fire.
 *
 * The default in-memory impl
 * ([com.astarworks.astera.match.application.service.InMemoryAnomalyScheduleRepository])
 * holds the canonical state; the Persistence plugin (Wave 5e+) registers a
 * Postgres-backed override at higher priority.
 *
 * Single-threaded by contract — populated at match start, read on the server
 * main thread.
 */
public interface IAnomalyScheduleRepository {
    /** Descriptors registered for [matchId]. Empty list if none. */
    public fun forMatch(matchId: MatchId): List<AnomalyDescriptor>

    /** Replace the descriptor list for [matchId] wholesale. */
    public fun setForMatch(matchId: MatchId, descriptors: List<AnomalyDescriptor>)
}
