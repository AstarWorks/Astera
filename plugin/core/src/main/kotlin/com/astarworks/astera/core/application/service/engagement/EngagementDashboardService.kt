package com.astarworks.astera.core.application.service.engagement

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.analytics.IAnalyticsExporter
import com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository
import com.astarworks.astera.core.domain.engagement.DauWauMau
import com.astarworks.astera.core.domain.engagement.EngagementDashboardSnapshot
import com.astarworks.astera.core.domain.engagement.EngagementScore
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.engagement.RetentionCohort
import com.astarworks.astera.core.domain.engagement.RetentionDay
import com.astarworks.astera.core.domain.engagement.RetentionRate
import com.astarworks.astera.core.domain.event.EngagementSnapshotComputed
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Computes the server-wide [EngagementDashboardSnapshot] (Wave 5engage, ADR-0104).
 *
 * Two public entrypoints:
 *
 *  - [recordPlayerActivity]: dashboard service's *input* side — called by
 *    the Wave 5engage bridge (or directly by tests) when a player's raw
 *    inputs change. Computes the [EngagementScore], persists the snapshot
 *    via [IEngagementSnapshotRepository], and fires the per-player gauge
 *    update on [IAnalyticsExporter].
 *  - [compute]: dashboard service's *output* side — assembles the
 *    server-wide [EngagementDashboardSnapshot], publishes
 *    [EngagementSnapshotComputed] on the event bus, and updates DAU/WAU/MAU
 *    + per-cohort gauges on [IAnalyticsExporter]. Intended to be called on
 *    a schedule (the wiring in `AsteraCorePlugin` registers a periodic task).
 *
 * # Why the service owns both sides
 *
 * Splitting into "writer" and "reader" services would need each one to know
 * the scoring policy and the cohort bucketing — keeping them together means
 * the policy is in exactly one place. The repository is the boundary that
 * makes per-player and aggregate reads cheap (single-row vs. table scan).
 *
 * # Top-N publishing
 *
 * Per-player gauges are only published for the top-N (default 100) so the
 * per-player Prometheus cardinality stays bounded — ADR-0104 §Mitigations.
 * The full dashboard snapshot still carries the top-N list for REST consumers.
 */
public class EngagementDashboardService(
    private val repository: IEngagementSnapshotRepository,
    private val exporter: IAnalyticsExporter,
    private val eventBus: IDomainEventBus,
    private val clock: () -> Instant = Instant::now,
    private val topNForGauges: Int = DEFAULT_TOP_N,
    private val retentionBins: List<RetentionDay> = RetentionDay.STANDARD_BINS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(topNForGauges >= 0) { "topNForGauges must be >= 0, was $topNForGauges" }
    }

    /**
     * Recompute and persist one player's snapshot. Called by adapters when
     * the raw inputs (playtime / streak / skills) change. The compiled score
     * is published to the per-player gauge if the player makes the top-N
     * after this update.
     */
    public fun recordPlayerActivity(
        playerId: PlayerId,
        totalPlaytime: Duration,
        loginStreakDays: Int,
        skillsExercisedCount: Int,
        refinementAttempts: Long,
        cosmeticEquipDiversity: Double,
        firstSeenAt: Instant,
        lastSeenAt: Instant,
    ) {
        val now = clock()
        val score = EngagementScoringPolicy.score(
            totalPlaytime = totalPlaytime,
            loginStreakDays = loginStreakDays,
            skillsExercisedCount = skillsExercisedCount,
            lastSeenAt = lastSeenAt,
            now = now,
        )
        val snapshot = EngagementSnapshot(
            playerId = playerId,
            totalPlaytime = totalPlaytime,
            loginStreakDays = loginStreakDays,
            skillsExercisedCount = skillsExercisedCount,
            refinementAttempts = refinementAttempts,
            cosmeticEquipDiversity = cosmeticEquipDiversity,
            firstSeenAt = firstSeenAt,
            lastSeenAt = lastSeenAt,
            score = score,
            computedAt = now,
        )
        repository.upsert(snapshot)
        publishTopNGauges()
    }

    /**
     * Assemble the server-wide [EngagementDashboardSnapshot] from the
     * persisted per-player snapshots. Pure function of repository state +
     * current clock; safe to call from any thread the dashboard scheduler chooses.
     */
    public fun compute(): EngagementDashboardSnapshot {
        val now = clock()
        val all = repository.all()
        val dauWauMau = computeDauWauMau(all, now)
        val retention = computeRetention(all, now)
        val top = repository.topByScore(topNForGauges)
        val snapshot = EngagementDashboardSnapshot(
            dauWauMau = dauWauMau,
            retentionByCohort = retention,
            topEngaged = top,
            computedAt = now,
        )

        // Publish DAU/WAU/MAU gauges + per-cohort gauges to the exporter.
        exporter.setDauWauMau(dauWauMau.dau, dauWauMau.wau, dauWauMau.mau)
        retention.forEach { (cohort, byDay) ->
            byDay.forEach { (day, rate) ->
                exporter.setRetentionCohort(
                    cohort = cohort.id,
                    day = "d${day.day}",
                    rate = rate.value,
                )
            }
        }
        publishTopNGauges()

        eventBus.publish(EngagementSnapshotComputed(snapshot = snapshot, occurredAt = now))
        log.debug(
            "compute() dau={} wau={} mau={} cohorts={} top={}",
            dauWauMau.dau, dauWauMau.wau, dauWauMau.mau, retention.size, top.size,
        )
        return snapshot
    }

    private fun publishTopNGauges() {
        repository.topByScore(topNForGauges).forEach { snap ->
            exporter.setPlayerEngagementScore(snap.playerId.uuid.toString(), snap.score.value)
        }
    }

    private fun computeDauWauMau(all: List<EngagementSnapshot>, now: Instant): DauWauMau {
        val dayCutoff = now.minus(Duration.ofDays(1))
        val weekCutoff = now.minus(Duration.ofDays(7))
        val monthCutoff = now.minus(Duration.ofDays(30))

        var dau = 0L
        var wau = 0L
        var mau = 0L
        for (snap in all) {
            // "isAfter" is strict; "lastSeenAt == cutoff" is *not* counted —
            // which matches Prometheus convention (gauges sample at scrape, not bin-edge inclusive).
            if (snap.lastSeenAt.isAfter(monthCutoff)) mau++
            if (snap.lastSeenAt.isAfter(weekCutoff)) wau++
            if (snap.lastSeenAt.isAfter(dayCutoff)) dau++
        }
        return DauWauMau(dau = dau, wau = wau, mau = mau)
    }

    private fun computeRetention(
        all: List<EngagementSnapshot>,
        now: Instant,
    ): Map<RetentionCohort, Map<RetentionDay, RetentionRate>> {
        if (all.isEmpty()) return emptyMap()

        // Bucket by first-seen *date* (UTC) → cohort.
        val byCohort: Map<RetentionCohort, List<EngagementSnapshot>> = all
            .groupBy { RetentionCohort(it.firstSeenAt.atZone(ZoneOffset.UTC).toLocalDate()) }

        return byCohort.mapValues { (cohort, members) ->
            val cohortSize = members.size
            retentionBins.associateWith { day ->
                // A cohort member is "retained on day N" iff their lastSeenAt
                // is at-or-after (firstSeenAt + N days). Equivalent: any
                // session anchored ≥ N days into their lifecycle.
                val cohortStart = cohort.firstSessionDate.atStartOfDay(ZoneOffset.UTC).toInstant()
                val retainedThreshold = cohortStart.plus(Duration.ofDays(day.day.toLong()))
                // Only count cohorts whose Nth day boundary has already
                // passed in wall-clock time — otherwise the rate is a
                // misleading underestimate ("D30 = 0% because we're only on
                // day 5"). Cohorts inside the bin window contribute 0
                // (presented as "not yet measurable").
                val boundaryReached = !now.isBefore(retainedThreshold)
                if (!boundaryReached) {
                    RetentionRate.ZERO
                } else {
                    val retained = members.count { !it.lastSeenAt.isBefore(retainedThreshold) }
                    RetentionRate(retained.toDouble() / cohortSize.toDouble())
                }
            }
        }
    }

    public companion object {
        /** Default per-player gauge cardinality cap. */
        public const val DEFAULT_TOP_N: Int = 100

        public val ZERO_SCORE: EngagementScore = EngagementScore.ZERO
    }
}
