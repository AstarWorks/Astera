package com.astarworks.astera.core.domain.engagement

import java.time.Instant

/**
 * Server-wide engagement aggregate (Wave 5engage, ADR-0104).
 *
 * Top-level value returned by `EngagementDashboardService.compute()`. Feeds:
 *  - Wave 12g `GET /api/retention` (admin-only)
 *  - Wave _ops Grafana `engagement.json` (5 panels: retention curve / DAU-MAU
 *    / per-skill heatmap / refinement attempt / cosmetic diversity)
 *  - Prometheus gauges via [com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter]
 *
 * Cohort retention is keyed `cohort → day → rate` (nested map) because:
 *  - cohorts are open-ended (new ones appear every day of server uptime)
 *  - days within a cohort are bounded (we only emit D1 / D7 / D30 today)
 *  - the alternative (flat list of `(cohort, day, rate)` triples) hides the
 *    invariant "every cohort answers each standard bin exactly once" — the
 *    nested shape makes the missing-bin case representable but unusual
 *
 * The `top` engagement-by-player list is bounded to a configurable cardinality
 * (default 100) so dashboards don't try to render 10 000 rows. Service-side
 * sort enforces descending score; ties broken by playerId for determinism.
 *
 * `computedAt` lets clients reason about staleness — Wave _ops alert rule
 * could fire if `now() - computedAt > 15 min` (recompute job stuck).
 */
public data class EngagementDashboardSnapshot(
    val dauWauMau: DauWauMau,
    val retentionByCohort: Map<RetentionCohort, Map<RetentionDay, RetentionRate>>,
    val topEngaged: List<EngagementSnapshot>,
    val computedAt: Instant,
) {
    init {
        // top-engaged list is server-rendered: must be sorted desc by score.
        val sortedScores = topEngaged.map { it.score.value }
        require(sortedScores == sortedScores.sortedDescending()) {
            "topEngaged must be sorted descending by score, was $sortedScores"
        }
    }

    public companion object {
        public fun empty(at: Instant = Instant.EPOCH): EngagementDashboardSnapshot =
            EngagementDashboardSnapshot(
                dauWauMau = DauWauMau.EMPTY,
                retentionByCohort = emptyMap(),
                topEngaged = emptyList(),
                computedAt = at,
            )
    }
}
