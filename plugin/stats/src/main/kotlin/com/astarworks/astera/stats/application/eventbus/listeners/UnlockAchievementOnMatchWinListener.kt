package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.stats.application.usecase.achievement.AchievementError
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.core.domain.achievement.AchievementId
import org.slf4j.LoggerFactory

/**
 * On every [MatchEnded], inspect each player's updated `wins` counter and
 * unlock `wins-1` / `wins-10` / `wins-50` as the counter crosses each
 * threshold (Wave 5e).
 *
 * # Why iterate `stats.all()` and not the match's winning team?
 *
 * The handoff scopes this listener to ctor `(unlock, stats)` only — no
 * `IMatchRepository`. We instead iterate every known player and rely on
 * `UnlockAchievementUseCase.unlock` being idempotent (re-attempts surface
 * [AchievementError.AlreadyUnlocked], which we swallow). The iteration is
 * O(#players-with-stats); on a Phase 1 PoC server (≤ 16 concurrent players,
 * per ADR-0006) this is cheaper than another `IMatchRepository` lookup and
 * keeps the dependency graph minimal.
 *
 * # Wiring order (Phase C critical)
 *
 * `RecordMatchResultOnMatchEndedListener` MUST be subscribed to
 * `MatchEnded` BEFORE this listener so the per-player `wins` counter is
 * incremented before we probe it. Subscription order is dispatch order in
 * `InMemoryDomainEventBus`.
 */
public class UnlockAchievementOnMatchWinListener(
    private val unlock: UnlockAchievementUseCase,
    private val stats: IPlayerStatsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNUSED_PARAMETER")
    public suspend fun handle(event: MatchEnded) {
        for (playerStats in stats.all()) {
            val wins = playerStats.wins
            for ((threshold, id) in THRESHOLDS) {
                if (wins < threshold) continue
                unlock.unlock(playerStats.playerId, id)
                    .onFailure { err ->
                        if (err !is AchievementError.AlreadyUnlocked &&
                            err !is AchievementError.NotInCatalog
                        ) {
                            log.debug(
                                "UnlockAchievementOnMatchWinListener: unexpected err for {} / {}: {}",
                                playerStats.playerId, id, err,
                            )
                        }
                    }
            }
        }
    }

    private companion object {
        val THRESHOLDS: List<Pair<Long, AchievementId>> = listOf(
            1L to AchievementId("wins-1"),
            10L to AchievementId("wins-10"),
            50L to AchievementId("wins-50"),
        )
    }
}
