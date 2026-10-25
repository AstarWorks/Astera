package com.astarworks.astera.core.domain.advancement

import java.time.Instant

/**
 * Per-player snapshot of progress against a single [CustomAdvancement]
 * (Wave 5p, ADR-0093).
 *
 * Stored row-per-(player, advancement) in `player_advancement_progress`
 * (V26 migration).
 *
 *  - [current] / [target] are the **dominant leaf counter** for the advancement
 *    (composite criteria pick the most-progressed branch for HUD display;
 *    completion is decided by the full criteria evaluation, not these numbers).
 *  - [completedAt] is non-null once the advancement has been earned. Once set
 *    it never unsets — re-grants are idempotent (see
 *    [com.astarworks.astera.core.application.usecase.advancement.ProgressAdvancementUseCase]).
 */
public data class AdvancementProgress(
    public val current: Int,
    public val target: Int,
    public val completedAt: Instant?,
) {
    public val isComplete: Boolean get() = completedAt != null

    /** Fraction in [0.0, 1.0]. Useful for HUD progress bars. */
    public fun fraction(): Double = when {
        target <= 0 -> 0.0
        current >= target -> 1.0
        else -> current.toDouble() / target.toDouble()
    }
}
