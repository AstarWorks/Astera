package com.astarworks.astera.economy.application.port.outbound.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.betting.BettingPolicy
import com.astarworks.astera.economy.domain.betting.LossStreakState
import java.time.Instant

/**
 * Outbound port: rolling per-player aggregates the InitiateBettingChallengeUseCase
 * needs to enforce the [BettingPolicy] constraints (Wave 5bet / ADR-0103).
 *
 * Persistent semantics — the Postgres overlay (Wave 5bet, V38 +
 * derived-aggregate views) reads from `betting_matches` to compute these.
 * The InMemory impl maintains the aggregates in-process for tests and
 * Phase 1 boot.
 */
public interface IBettingHistoryService {

    /**
     * Sum of stake amounts in [currency] that [playerId] has wagered since
     * [sinceUtcDayStart] (typically "start of current UTC day"). Counts
     * only matches that progressed past `PendingAcceptance` (i.e. accepted —
     * declined / expired stakes are NOT counted against the daily cap).
     */
    public suspend fun dailyStakeFor(
        playerId: PlayerId,
        currency: Currency,
        sinceUtcDayStart: Instant,
    ): Long

    /**
     * The time at which [playerId] is allowed to issue / accept their next
     * challenge. Returns null if no cooldown is active. Computed from the
     * most recent match's `resolvedAt + policy.cooldownAfterMatch`.
     */
    public suspend fun cooldownUntil(playerId: PlayerId): Instant?

    /** Get-or-create the [LossStreakState] for [playerId]. Default = empty. */
    public suspend fun lossStreakOf(playerId: PlayerId): LossStreakState

    /**
     * Record [playerId]'s most recent match-resolution timestamp (used for
     * cooldown computation). Called from
     * `DistributeBettingPayoutOnResolvedListener`.
     */
    public suspend fun recordResolution(playerId: PlayerId, resolvedAt: Instant)

    /**
     * Persist the player's updated loss-streak state. Called from
     * `DistributeBettingPayoutOnResolvedListener` after each outcome.
     */
    public suspend fun saveLossStreak(state: LossStreakState)
}
