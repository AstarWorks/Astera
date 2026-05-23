package com.astarworks.astera.core.application.service.engagement

import com.astarworks.astera.core.domain.engagement.EngagementScore
import java.time.Duration
import java.time.Instant

/**
 * Pure scoring policy for [EngagementScore] (Wave 5engage, ADR-0104).
 *
 * Lives in application/service/ — *not* domain/ — because the formula is
 * tuneable: per-wave balance changes may reweight (or replace) the inputs
 * without touching the value type. Domain stays minimal (the scalar + range
 * invariant); the rule lives here, version-bumped per wave.
 *
 * # Phase 1 (v1) formula
 *
 * Weighted sum of four normalised inputs, clamped to [0.0, 1.0]:
 *
 *  - **playtime** (weight 0.35): `min(totalHours / 100, 1.0)` —
 *    100h is the "regular player" anchor; further play does not raise the
 *    component (so a top-1% no-lifer doesn't outscore a balanced player
 *    by 10×).
 *  - **streak** (weight 0.25): `min(loginStreakDays / 30, 1.0)` —
 *    30-day streak caps the component; the daily-grind incentive flattens
 *    after a month to discourage burnout.
 *  - **skill diversity** (weight 0.2): `min(skillsExercisedCount / 10, 1.0)` —
 *    using ≥10 distinct skills (within 30-day window) signals investment
 *    in the build / progression layer (ADR-0101 multi-skill).
 *  - **recency** (weight 0.2): `1.0 - min(daysSinceLastSeen / 14, 1.0)` —
 *    decays linearly to 0 over 14 days of absence. A player who hasn't
 *    logged in for 2 weeks is, by definition, not engaged today.
 *
 * Refinement attempts + cosmetic diversity are kept on the snapshot for
 * Wave 12g panel rendering but are not yet inputs to v1 — they activate in
 * v2 once Wave 5refine (ADR-0100) and Wave 5flex (ADR-0102) integrate.
 *
 * # Determinism
 *
 * The formula is a pure function of the inputs + a `now` clock. Tests pass
 * an explicit `now` to verify recency decay without `Instant.now()` flakiness.
 */
public object EngagementScoringPolicy {

    private const val PLAYTIME_WEIGHT = 0.35
    private const val STREAK_WEIGHT = 0.25
    private const val SKILL_WEIGHT = 0.20
    private const val RECENCY_WEIGHT = 0.20

    private const val PLAYTIME_CAP_HOURS = 100.0
    private const val STREAK_CAP_DAYS = 30.0
    private const val SKILL_CAP_COUNT = 10.0
    private const val RECENCY_DECAY_DAYS = 14.0

    public fun score(
        totalPlaytime: Duration,
        loginStreakDays: Int,
        skillsExercisedCount: Int,
        lastSeenAt: Instant,
        now: Instant,
    ): EngagementScore {
        val playtimeHours = totalPlaytime.toMinutes() / 60.0
        val playtimeComponent = (playtimeHours / PLAYTIME_CAP_HOURS).coerceIn(0.0, 1.0)
        val streakComponent = (loginStreakDays / STREAK_CAP_DAYS).coerceIn(0.0, 1.0)
        val skillComponent = (skillsExercisedCount / SKILL_CAP_COUNT).coerceIn(0.0, 1.0)

        val daysSinceLastSeen = Duration.between(lastSeenAt, now).toMinutes() / (60.0 * 24.0)
        val recencyComponent = (1.0 - (daysSinceLastSeen / RECENCY_DECAY_DAYS)).coerceIn(0.0, 1.0)

        val combined = playtimeComponent * PLAYTIME_WEIGHT +
            streakComponent * STREAK_WEIGHT +
            skillComponent * SKILL_WEIGHT +
            recencyComponent * RECENCY_WEIGHT

        return EngagementScore(combined.coerceIn(0.0, 1.0))
    }
}
