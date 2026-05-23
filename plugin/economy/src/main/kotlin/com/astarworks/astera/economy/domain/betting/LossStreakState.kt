package com.astarworks.astera.economy.domain.betting

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Per-player loss-streak counter (Wave 5bet / ADR-0103 §"Decision" /
 * `BettingPolicy.lossStreakLimit`).
 *
 * Maintained by `IBettingHistoryService` (the in-memory + Postgres-overlay
 * outbound port). A reset happens whenever a win is recorded; reaching the
 * configured limit triggers a [cooldownEndsAt] window during which
 * `InitiateBettingChallengeUseCase` rejects with
 * [BettingError.LossStreakLimitReached].
 */
public data class LossStreakState(
    val playerId: PlayerId,
    val consecutiveLosses: Int,
    val lastLossAt: Instant?,
    val cooldownEndsAt: Instant?,
) {
    init {
        require(consecutiveLosses >= 0) { "consecutiveLosses must be >= 0, got $consecutiveLosses" }
    }

    /**
     * Apply a single loss outcome. If [consecutiveLosses + 1] reaches [limit],
     * also opens a [cooldownEndsAt] window of [cooldownMs] milliseconds from
     * [now].
     */
    public fun applyLoss(now: Instant, limit: Int, cooldownMs: Long): LossStreakState {
        val next = consecutiveLosses + 1
        val newCooldownEnds = if (next >= limit) now.plusMillis(cooldownMs) else cooldownEndsAt
        return copy(
            consecutiveLosses = next,
            lastLossAt = now,
            cooldownEndsAt = newCooldownEnds,
        )
    }

    /** Reset streak — fired on win / draw / cancel. Preserves the prior [cooldownEndsAt] if still in the future. */
    public fun reset(now: Instant): LossStreakState {
        val keepCooldown = cooldownEndsAt?.takeIf { it.isAfter(now) }
        return copy(
            consecutiveLosses = 0,
            cooldownEndsAt = keepCooldown,
        )
    }

    public fun isCoolingDown(now: Instant): Boolean =
        cooldownEndsAt != null && cooldownEndsAt.isAfter(now)

    public companion object {
        public fun empty(playerId: PlayerId): LossStreakState = LossStreakState(
            playerId = playerId,
            consecutiveLosses = 0,
            lastLossAt = null,
            cooldownEndsAt = null,
        )
    }
}
