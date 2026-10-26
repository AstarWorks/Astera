package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.engagement.EngagementDashboardSnapshot
import java.time.Instant

/**
 * Fires after `EngagementDashboardService.compute()` finalizes a server-wide
 * dashboard pass (Wave 5engage, ADR-0104).
 *
 * Subscribers:
 *  - Wave _ops Prometheus exporter (gauges: DAU/WAU/MAU + per-cohort retention)
 *  - Future Wave 8b REST handler (cache invalidation: next `/api/retention`
 *    request serves the fresh snapshot without recomputing)
 *  - Future Wave 8a Web admin: WebSocket push to live admin dashboard
 *
 * Why the full snapshot is in the event (not a "fetch me" handle):
 *  - Subscribers don't need a port lookup — they read what they need straight off the event.
 *  - The snapshot is bounded (top-N players, fixed cohort bins) so the event
 *    payload is small.
 *  - Matches the pattern of `MatchEnded.winningTeam` / `LifecycleSavedSnapshot.scope`
 *    — domain events carry the **fact**, not a pointer to it.
 */
public data class EngagementSnapshotComputed(
    val snapshot: EngagementDashboardSnapshot,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
