package com.astarworks.astera.core.domain.engagement

import java.time.LocalDate

/**
 * A cohort of players keyed by their first-session date (Wave 5engage, ADR-0104).
 *
 * Players who first joined on `2026-05-20` share `RetentionCohort(LocalDate.of(2026, 5, 20))`.
 * The dashboard groups retention by cohort so designers can answer "did the
 * 5/20 patch raise D7 retention?" without conflating new-user behavior with
 * existing users.
 *
 * Granularity is intentionally **day**, not week: weekly cohorting hides
 * weekend / weekday effects that matter for a Minecraft server. Aggregating
 * up to weeks is a presentation concern (Grafana / Web admin).
 */
@JvmInline
public value class RetentionCohort(public val firstSessionDate: LocalDate) {

    /** ISO-8601 yyyy-MM-dd, used as the JSONB key + Prometheus label value. */
    public val id: String get() = firstSessionDate.toString()
}

/**
 * "Days since cohort's first session" bin — D1 / D7 / D30 in standard
 * retention parlance. Modeled as an inline value class so a `Map<RetentionDay, Double>`
 * is type-safe at the call site (vs. a stringly-typed map keyed by `"d7"`).
 */
@JvmInline
public value class RetentionDay(public val day: Int) {
    init {
        require(day >= 0) { "RetentionDay must be >= 0, was $day" }
    }

    public companion object {
        public val D1: RetentionDay = RetentionDay(1)
        public val D7: RetentionDay = RetentionDay(7)
        public val D30: RetentionDay = RetentionDay(30)

        /** The standard bins the dashboard tracks. */
        public val STANDARD_BINS: List<RetentionDay> = listOf(D1, D7, D30)
    }
}

/**
 * Retention rate for a (cohort, day) pair: fraction of the cohort that
 * returned on day N. 0.0..1.0 inclusive.
 */
@JvmInline
public value class RetentionRate(public val value: Double) {
    init {
        require(value in 0.0..1.0) {
            "RetentionRate must be in [0.0, 1.0], was $value"
        }
        require(!value.isNaN()) {
            "RetentionRate must not be NaN"
        }
    }

    public companion object {
        public val ZERO: RetentionRate = RetentionRate(0.0)
        public val FULL: RetentionRate = RetentionRate(1.0)
    }
}
