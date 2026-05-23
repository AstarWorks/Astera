package com.astarworks.astera.economy.application.service.betting

import com.astarworks.astera.core.domain.economy.Currency
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingHistoryService
import com.astarworks.astera.economy.application.port.outbound.betting.IBettingMatchRepository
import com.astarworks.astera.economy.domain.betting.BettingStatus
import com.astarworks.astera.economy.domain.betting.LossStreakState
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IBettingHistoryService] (Wave 5bet / ADR-0103).
 *
 * The daily-stake aggregation walks the [matches] repository on every call —
 * fine for Phase 1 single-server load (PoC closing). The Postgres overlay
 * (planned for V38 + materialized view) replaces this with a SQL aggregate.
 *
 * # Cooldown semantics
 *
 * [recordResolution] is invoked per-player by the payout listener with the
 * resolved-at instant. The InitiateBettingChallengeUseCase computes
 * `cooldownEnd = lastResolvedAt + policy.cooldownAfterMatch` and gates new
 * challenges via [cooldownUntil].
 */
public class InMemoryBettingHistoryService(
    private val matches: IBettingMatchRepository,
    private val cooldownAfterMatchMs: Long,
) : IBettingHistoryService {

    private val lastResolvedAt: MutableMap<PlayerId, Instant> = ConcurrentHashMap()
    private val streaks: MutableMap<PlayerId, LossStreakState> = ConcurrentHashMap()

    override fun dailyStakeFor(
        playerId: PlayerId,
        currency: Currency,
        sinceUtcDayStart: Instant,
    ): Long {
        // Only accepted (escrowed) stakes count; pending / declined / expired don't.
        return matches.recentForPlayer(playerId, limit = Int.MAX_VALUE)
            .asSequence()
            .filter { it.stake.currency == currency }
            .filter { it.acceptedAt != null && it.acceptedAt >= sinceUtcDayStart }
            .filter {
                it.status == BettingStatus.Active ||
                    it.status == BettingStatus.Resolved ||
                    it.status == BettingStatus.Cancelled
            }
            .filter { it.challengerId == playerId || it.opponentId == playerId }
            .sumOf { it.stake.amount }
    }

    override fun cooldownUntil(playerId: PlayerId): Instant? {
        val last = lastResolvedAt[playerId] ?: return null
        return last.plusMillis(cooldownAfterMatchMs)
    }

    override fun lossStreakOf(playerId: PlayerId): LossStreakState =
        streaks[playerId] ?: LossStreakState.empty(playerId)

    override fun recordResolution(playerId: PlayerId, resolvedAt: Instant) {
        lastResolvedAt.merge(playerId, resolvedAt) { existing, incoming ->
            if (incoming.isAfter(existing)) incoming else existing
        }
    }

    override fun saveLossStreak(state: LossStreakState) {
        streaks[state.playerId] = state
    }

    /** Phase 1 helper for tests: drop everything. */
    public fun clear() {
        lastResolvedAt.clear()
        streaks.clear()
    }
}
