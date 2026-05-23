package com.astarworks.astera.core.adapter.paper.analytics

import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Micrometer-backed [IAnalyticsExporter] adapter (Wave 5u, ADR-0087).
 *
 * Forwards every business-KPI record into the same [MeterRegistry] that backs
 * the Wave _ops Prometheus scrape endpoint, so KPI dashboards and KEDA scaling
 * read the same process-wide source of truth.
 *
 * # Label cardinality discipline
 *
 * Labels are bounded by the manifest at
 * `content/observability/metric-definitions.yaml`:
 *  - `item_id` / `skill_id` / `currency` / `winning_team` — finite content
 *    enumerations, naturally bounded
 *  - `match_id` — high-card but bounded per session (lifetime ≤ wall-clock
 *    server uptime); gauge entries auto-evict via [unregisterMatch] on
 *    MatchEnded
 *
 * # Why histograms via DistributionSummary
 *
 * Micrometer's `Timer` is the natural fit for duration metrics, but
 * `recordX(durationSeconds: Double)` is a value-record signature (caller
 * holds the duration already; we don't time around them). Using
 * [DistributionSummary] keeps the API simple: callers pass an already-known
 * seconds value, the Prometheus exposition still emits histogram buckets.
 */
public class MicrometerAnalyticsExporter(
    private val registry: MeterRegistry,
) : IAnalyticsExporter {

    private val log = LoggerFactory.getLogger(javaClass)

    // Match-scope gauge bookkeeping: one AtomicLong per match for player count.
    // ConcurrentHashMap so MatchStarted publish + MatchEnded eviction can race
    // without ConcurrentModification. AtomicReference so we can `null` the
    // backing supplier on un-register without re-creating the gauge in
    // Micrometer (gauges in Micrometer dedupe by name+tags; once registered
    // they live until the registry's lifetime ends).
    private val matchPlayerCounts: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    private val marketplaceListingCount = AtomicLong(0)

    // Wave 5engage — DAU/WAU/MAU gauge holders. AtomicLong + supplier-pattern
    // (Gauge.builder reads on scrape) so updates from the dashboard service
    // thread land atomically without registering new meters.
    private val dauHolder = AtomicLong(0)
    private val wauHolder = AtomicLong(0)
    private val mauHolder = AtomicLong(0)

    // Wave 5engage — per-player score gauge bookkeeping. Same shape as
    // matchPlayerCounts: AtomicLong per player; computeIfAbsent registers the
    // Micrometer gauge once. The dashboard service is expected to call this
    // only for the top-N players to bound cardinality (see ADR-0104 §Privacy).
    private val playerEngagementScores: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    // Wave 5engage — retention cohort gauge bookkeeping (cohort_iso × day_label → AtomicLong).
    // Key encodes (cohort, day) — the gauge is registered once per pair.
    // AtomicLong stores the score scaled by 10_000 because AtomicLong is integral;
    // the gauge supplier divides back to a 0.0..1.0 double on scrape.
    private val retentionCohorts: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    init {
        // Register the marketplace listing gauge once, supplier reads the AtomicLong.
        io.micrometer.core.instrument.Gauge.builder(METRIC_MARKETPLACE_LISTING_COUNT) {
            marketplaceListingCount.get().toDouble()
        }.register(registry)
        // Wave 5engage gauges — DAU/WAU/MAU are registered once and read on scrape.
        io.micrometer.core.instrument.Gauge.builder(METRIC_DAU) {
            dauHolder.get().toDouble()
        }.register(registry)
        io.micrometer.core.instrument.Gauge.builder(METRIC_WAU) {
            wauHolder.get().toDouble()
        }.register(registry)
        io.micrometer.core.instrument.Gauge.builder(METRIC_MAU) {
            mauHolder.get().toDouble()
        }.register(registry)
        log.debug("MicrometerAnalyticsExporter initialised")
    }

    override fun recordMatchEnded(
        matchId: String,
        durationSeconds: Double,
        playerCount: Int,
        winningTeam: String?,
    ) {
        val winningTeamTag = winningTeam ?: "none"
        DistributionSummary.builder(METRIC_MATCH_DURATION_SECONDS)
            .tag("winning_team", winningTeamTag)
            .baseUnit("seconds")
            .register(registry)
            .record(durationSeconds)
        // Match has ended: drop the per-match player-count gauge to avoid
        // accumulating dead entries over server lifetime.
        matchPlayerCounts.remove(matchId)
        log.debug(
            "recordMatchEnded(match={}, duration={}s, players={}, winner={})",
            matchId, durationSeconds, playerCount, winningTeamTag,
        )
    }

    override fun recordKill(
        matchId: String,
        attackerId: String,
        victimId: String,
        itemId: String,
    ) {
        Counter.builder(METRIC_MATCH_KILL_COUNT_TOTAL)
            .tag("item_id", itemId)
            .register(registry)
            .increment()
        // Per-player kill counter (no label → low cardinality, single timeseries).
        Counter.builder(METRIC_PLAYER_KILL_COUNT_TOTAL)
            .register(registry)
            .increment()
    }

    override fun recordDeath(playerId: String, matchId: String) {
        Counter.builder(METRIC_PLAYER_DEATH_COUNT_TOTAL)
            .register(registry)
            .increment()
    }

    override fun recordPlayerSessionEnded(playerId: String, durationSeconds: Double) {
        DistributionSummary.builder(METRIC_PLAYER_SESSION_DURATION_SECONDS)
            .baseUnit("seconds")
            .register(registry)
            .record(durationSeconds)
    }

    override fun recordSkillCast(skillId: String, casterId: String) {
        Counter.builder(METRIC_SKILL_CAST_TOTAL)
            .tag("skill_id", skillId)
            .register(registry)
            .increment()
    }

    override fun recordShopRevenue(currency: String, amount: Long) {
        Counter.builder(METRIC_SHOP_REVENUE_TOTAL)
            .tag("currency", currency)
            .register(registry)
            .increment(amount.toDouble())
    }

    override fun setMarketplaceListingCount(count: Long) {
        marketplaceListingCount.set(count)
    }

    override fun setMatchPlayerCount(matchId: String, count: Int) {
        val holder = matchPlayerCounts.computeIfAbsent(matchId) {
            val al = AtomicLong(0)
            io.micrometer.core.instrument.Gauge.builder(METRIC_MATCH_PLAYER_COUNT) {
                al.get().toDouble()
            }
                .tag("match_id", matchId)
                .register(registry)
            al
        }
        holder.set(count.toLong())
    }

    // ─── Wave 5engage (ADR-0104) ───

    override fun setPlayerEngagementScore(playerId: String, score: Double) {
        val holder = playerEngagementScores.computeIfAbsent(playerId) {
            val al = AtomicLong(0)
            io.micrometer.core.instrument.Gauge.builder(METRIC_PLAYER_ENGAGEMENT_SCORE) {
                // Stored ×10_000 so the AtomicLong holds an int-shaped value;
                // divide on read to recover the 0.0..1.0 gauge value.
                al.get() / 10_000.0
            }
                .tag("player_id", playerId)
                .register(registry)
            al
        }
        holder.set((score.coerceIn(0.0, 1.0) * 10_000.0).toLong())
    }

    override fun setDauWauMau(dau: Long, wau: Long, mau: Long) {
        dauHolder.set(dau)
        wauHolder.set(wau)
        mauHolder.set(mau)
    }

    override fun setRetentionCohort(cohort: String, day: String, rate: Double) {
        val key = "$cohort|$day"
        val holder = retentionCohorts.computeIfAbsent(key) {
            val al = AtomicLong(0)
            io.micrometer.core.instrument.Gauge.builder(METRIC_RETENTION_COHORT) {
                al.get() / 10_000.0
            }
                .tag("cohort", cohort)
                .tag("day", day)
                .register(registry)
            al
        }
        holder.set((rate.coerceIn(0.0, 1.0) * 10_000.0).toLong())
    }

    public companion object {
        public const val METRIC_MATCH_DURATION_SECONDS: String = "astera_match_duration_seconds"
        public const val METRIC_MATCH_KILL_COUNT_TOTAL: String = "astera_match_kill_count"
        public const val METRIC_MATCH_PLAYER_COUNT: String = "astera_match_player_count"
        public const val METRIC_PLAYER_SESSION_DURATION_SECONDS: String = "astera_player_session_duration_seconds"
        public const val METRIC_PLAYER_KILL_COUNT_TOTAL: String = "astera_player_kill_count"
        public const val METRIC_PLAYER_DEATH_COUNT_TOTAL: String = "astera_player_death_count"
        public const val METRIC_SHOP_REVENUE_TOTAL: String = "astera_shop_revenue"
        public const val METRIC_MARKETPLACE_LISTING_COUNT: String = "astera_marketplace_listing_count"
        public const val METRIC_SKILL_CAST_TOTAL: String = "astera_skill_cast"

        // Wave 5engage (ADR-0104)
        public const val METRIC_PLAYER_ENGAGEMENT_SCORE: String = "astera_player_engagement_score"
        public const val METRIC_DAU: String = "astera_dau"
        public const val METRIC_WAU: String = "astera_wau"
        public const val METRIC_MAU: String = "astera_mau"
        public const val METRIC_RETENTION_COHORT: String = "astera_retention_cohort"
    }
}
