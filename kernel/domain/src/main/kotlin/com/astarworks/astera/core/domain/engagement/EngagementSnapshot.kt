package com.astarworks.astera.core.domain.engagement

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Duration
import java.time.Instant

/**
 * Per-player engagement state at a point in time (Wave 5engage, ADR-0104).
 *
 * One row per player; latest-write-wins (the dashboard service overwrites on
 * every recompute). The raw inputs are kept alongside the derived [score] so
 * the REST endpoint can echo the breakdown ("playtime 4h / streak 3d /
 * skills 2 / last seen 5 min ago") without re-deriving from per-skill or
 * per-session tables.
 *
 * # Inputs (raw, dashboard-owned)
 *
 *  - [totalPlaytime]: cumulative session duration across all known sessions.
 *    Sourced from [com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository]
 *    via the dashboard service; durable across server hops (ADR-0068).
 *  - [loginStreakDays]: consecutive calendar days with at least one session.
 *    Resets to 1 on the first session after a gap day.
 *  - [skillsExercisedCount]: distinct skill ids cast in the trailing 30-day
 *    window. Drives the "per-skill engagement" panel in Wave 12g §Metric.
 *  - [refinementAttempts]: cumulative refinement attempts (ADR-0100 / Wave 5refine).
 *    Phase 1 = always 0 (refinement service is not yet wired); the field is
 *    kept so the storage schema does not change when Wave 12c lands.
 *  - [cosmeticEquipDiversity]: 0.0..1.0 — fraction of owned cosmetics the
 *    player has ever equipped. Phase 1 = 0.0 (placeholder, ADR-0102 Wave 5flex
 *    integration arrives later).
 *  - [firstSeenAt] / [lastSeenAt]: cohort boundary + DAU/WAU/MAU input.
 *
 * # Derived
 *
 *  - [score]: 0.0..1.0 engagement scalar from
 *    [com.astarworks.astera.core.application.service.engagement.EngagementScoringPolicy].
 *    Stored alongside the inputs so dashboards do not need to re-evaluate the
 *    formula at query time; if the formula changes mid-wave, a recompute pass
 *    rewrites all rows.
 *
 * # Why one snapshot per player (not append-only history)
 *
 * Wave _ops Prometheus scrape preserves the time-series shape already
 * (`astera_player_engagement_score{playerId}` gauge over time). Keeping a
 * single row per player here matches `player_sessions` (Wave _persist-1 /
 * ADR-0068) which faced the same choice and chose latest-write-wins. The
 * history lives in the metric backend; this table holds the current state.
 */
public data class EngagementSnapshot(
    val playerId: PlayerId,
    val totalPlaytime: Duration,
    val loginStreakDays: Int,
    val skillsExercisedCount: Int,
    val refinementAttempts: Long,
    val cosmeticEquipDiversity: Double,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val score: EngagementScore,
    val computedAt: Instant,
) {
    init {
        require(!totalPlaytime.isNegative) {
            "totalPlaytime must not be negative, was $totalPlaytime"
        }
        require(loginStreakDays >= 0) {
            "loginStreakDays must be >= 0, was $loginStreakDays"
        }
        require(skillsExercisedCount >= 0) {
            "skillsExercisedCount must be >= 0, was $skillsExercisedCount"
        }
        require(refinementAttempts >= 0) {
            "refinementAttempts must be >= 0, was $refinementAttempts"
        }
        require(cosmeticEquipDiversity in 0.0..1.0) {
            "cosmeticEquipDiversity must be in [0.0, 1.0], was $cosmeticEquipDiversity"
        }
        require(!firstSeenAt.isAfter(lastSeenAt)) {
            "firstSeenAt ($firstSeenAt) must be <= lastSeenAt ($lastSeenAt)"
        }
    }
}
