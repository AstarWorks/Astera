package com.astarworks.astera.core.application.port.outbound.analytics

/**
 * Outbound port: business-KPI metric exporter (Wave 5u, ADR-0087).
 *
 * Sister port to [com.astarworks.astera.core.application.port.outbound.observability.IMetricsRegistry]
 * (Wave _ops). The role split is intentional:
 *
 *  - **IMetricsRegistry** = *technical* metrics: TPS, JVM heap, scrape
 *    endpoint shape, KEDA scaling signal (`astera_match_active`). One gauge
 *    per concern. Wave _ops level — what infra-ops cares about.
 *  - **IAnalyticsExporter** = *business* KPIs: per-match duration, per-player
 *    kills, per-skill cast counts, marketplace revenue per currency. Many
 *    timeseries with labels. Wave 5u level — what game designers / product
 *    cares about.
 *
 * silly-knuth §5: `recordX` calls cannot fail in any way the caller can act
 * on — metric publishing is fire-and-forget; backpressure is the exporter's
 * problem. No `Result<T, E>` here.
 *
 * Default adapter ([com.astarworks.astera.core.adapter.paper.analytics.MicrometerAnalyticsExporter])
 * forwards every record into the same `MeterRegistry` that backs Prometheus
 * scrape, so both surfaces share one process-wide source of truth.
 *
 * ## Metric naming convention
 *
 * Every metric is `astera_<domain>_<noun>[_total|_seconds]`, matching
 * Prometheus best practice. `_total` is reserved for monotonic counters
 * (PrometheusMeterRegistry appends it automatically to Micrometer Counters,
 * strips it from Gauges); `_seconds` is the canonical duration suffix for
 * histograms.
 */
public interface IAnalyticsExporter {

    /** Records that a match has just ended. */
    public fun recordMatchEnded(
        matchId: String,
        durationSeconds: Double,
        playerCount: Int,
        winningTeam: String?,
    )

    /** Records a kill in-match, labeled by the item id that delivered the lethal blow. */
    public fun recordKill(
        matchId: String,
        attackerId: String,
        victimId: String,
        itemId: String,
    )

    /** Records a player death (separate counter so the kill:death ratio is queryable). */
    public fun recordDeath(playerId: String, matchId: String)

    /** Records a player session ending — duration in seconds, used in histogram. */
    public fun recordPlayerSessionEnded(playerId: String, durationSeconds: Double)

    /** Records a skill cast, labeled by skill id (Wave 5a-fix2 trigger taxonomy). */
    public fun recordSkillCast(skillId: String, casterId: String)

    /** Records a shop / marketplace transaction, labeled by currency. */
    public fun recordShopRevenue(currency: String, amount: Long)

    /** Snapshot of currently-listed marketplace listings (gauge wrapper). */
    public fun setMarketplaceListingCount(count: Long)

    /** Per-match active player count gauge update. */
    public fun setMatchPlayerCount(matchId: String, count: Int)

    // ─── Wave 5engage (ADR-0104) — engagement dashboard metrics ──────────

    /**
     * Per-player engagement scalar (0.0..1.0). High cardinality on `playerId`;
     * the dashboard service samples the top-N players (default 100) per
     * compute pass and only re-publishes those gauges, so the steady-state
     * cardinality is bounded.
     */
    public fun setPlayerEngagementScore(playerId: String, score: Double)

    /**
     * Stickiness gauges: Daily / Weekly / Monthly active users at the latest
     * dashboard compute. Three separate gauges so PromQL can compute the
     * stickiness ratio directly (`astera_dau / astera_mau`).
     */
    public fun setDauWauMau(dau: Long, wau: Long, mau: Long)

    /**
     * Retention rate for a (cohort, day) bin (0.0..1.0). `cohort` is the
     * ISO-8601 first-session date (`2026-05-20`); `day` is the standard bin
     * label (`d1` / `d7` / `d30`). Cardinality is bounded by (server uptime
     * in days × number of bins) — for a 1-year-old server with the standard
     * 3 bins, that's ~1100 timeseries, well within Prometheus comfort zone.
     */
    public fun setRetentionCohort(cohort: String, day: String, rate: Double)
}
