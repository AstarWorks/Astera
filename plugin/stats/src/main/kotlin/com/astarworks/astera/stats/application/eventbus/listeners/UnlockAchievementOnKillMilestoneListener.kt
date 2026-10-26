package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.achievement.AchievementError
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.core.domain.achievement.AchievementId
import org.slf4j.LoggerFactory

/**
 * On every [PlayerKilled], inspect the killer's updated `kills` counter and
 * unlock `kills-10` / `kills-50` / `kills-100` as the counter crosses each
 * threshold (Wave 5e).
 *
 * # Wiring order (Phase C critical)
 *
 * [com.astarworks.astera.stats.application.eventbus.listeners.RewardOnKillListener]
 * MUST be subscribed to `PlayerKilled` BEFORE this listener so the kills
 * counter is incremented before we check the threshold. Subscription order
 * is dispatch order in `InMemoryDomainEventBus`.
 *
 * # Idempotency
 *
 * `UnlockAchievementUseCase` rejects re-unlocks with
 * [AchievementError.AlreadyUnlocked] — we swallow that silently because the
 * milestone check on each kill is intentional broadcast. We also tolerate
 * [AchievementError.NotInCatalog] silently: the admin may have chosen not to
 * register a given achievement id.
 *
 * Null killer (environment kill / disconnect) yields no work.
 */
public class UnlockAchievementOnKillMilestoneListener(
    private val unlock: UnlockAchievementUseCase,
    private val stats: IPlayerStatsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: PlayerKilled) {
        if (!event.context.countsForProgression) return // ADR-0235
        val killerId = event.killer ?: return
        val currentKills = stats.find(killerId)?.kills ?: return

        for ((threshold, id) in THRESHOLDS) {
            if (currentKills < threshold) continue
            unlock.unlock(killerId, id)
                .onFailure { err ->
                    if (err !is AchievementError.AlreadyUnlocked &&
                        err !is AchievementError.NotInCatalog
                    ) {
                        log.debug(
                            "UnlockAchievementOnKillMilestoneListener: unexpected err for {} / {}: {}",
                            killerId, id, err,
                        )
                    }
                }
        }
    }

    private companion object {
        val THRESHOLDS: List<Pair<Long, AchievementId>> = listOf(
            10L to AchievementId("kills-10"),
            50L to AchievementId("kills-50"),
            100L to AchievementId("kills-100"),
        )
    }
}
