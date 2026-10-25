package com.astarworks.astera.core.domain.engagement

/**
 * Aggregate per-player engagement score (Wave 5engage, ADR-0104).
 *
 * 0.0–1.0 normalized scalar derived from playtime, login streak, skill diversity,
 * and recency. Wave 12g REST endpoint `GET /api/players/<id>/engagement` exposes
 * the raw inputs alongside this scalar; the scalar drives the Grafana panel
 * `astera_player_engagement_score{playerId}` (gauge).
 *
 * The actual scoring formula lives in
 * [com.astarworks.astera.core.application.service.engagement.EngagementScoringPolicy]
 * — this domain type is the **value**, not the rule. Keeping the formula in
 * `application/` (not `domain/`) means the policy can evolve per-wave without
 * touching this value object (and therefore without breaking JSONB round-trip).
 *
 * Construction validates the range — silly-knuth §1: invalid states must be
 * unrepresentable, so callers cannot leak an out-of-band value into a gauge
 * that downstream Prometheus alerts treat as bounded.
 */
@JvmInline
public value class EngagementScore(public val value: Double) {
    init {
        require(value in 0.0..1.0) {
            "EngagementScore must be in [0.0, 1.0], was $value"
        }
        require(!value.isNaN()) {
            "EngagementScore must not be NaN"
        }
    }

    public companion object {
        public val ZERO: EngagementScore = EngagementScore(0.0)
        public val MAX: EngagementScore = EngagementScore(1.0)
    }
}
