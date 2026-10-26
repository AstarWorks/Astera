package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.achievement.AchievementError
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.core.domain.achievement.AchievementId
import org.slf4j.LoggerFactory

/**
 * On every [DamageDealt], inspect the attacker's running `damageDealt`
 * total and unlock `damage-1k` / `damage-10k` as the total crosses each
 * threshold (Wave 5e).
 *
 * # Wiring order (Phase C critical)
 *
 * The listener that increments `damageDealt` (currently the Reward-on-damage
 * pathway via `RecordDamageDealtUseCase` — registered in Phase C) MUST be
 * subscribed to `DamageDealt` before this listener; subscription order is
 * dispatch order in `InMemoryDomainEventBus`.
 *
 * Null attacker (environment damage) yields no work. `NotInCatalog` /
 * `AlreadyUnlocked` are tolerated silently — every damage event probes the
 * threshold by design.
 */
public class UnlockAchievementOnDamageMilestoneListener(
    private val unlock: UnlockAchievementUseCase,
    private val stats: IPlayerStatsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: DamageDealt) {
        if (!event.context.countsForProgression) return // ADR-0235
        val attackerId = event.attacker ?: return
        val total = stats.find(attackerId)?.damageDealt ?: return

        for ((threshold, id) in THRESHOLDS) {
            if (total < threshold) continue
            unlock.unlock(attackerId, id)
                .onFailure { err ->
                    if (err !is AchievementError.AlreadyUnlocked &&
                        err !is AchievementError.NotInCatalog
                    ) {
                        log.debug(
                            "UnlockAchievementOnDamageMilestoneListener: unexpected err for {} / {}: {}",
                            attackerId, id, err,
                        )
                    }
                }
        }
    }

    private companion object {
        val THRESHOLDS: List<Pair<Long, AchievementId>> = listOf(
            1_000L to AchievementId("damage-1k"),
            10_000L to AchievementId("damage-10k"),
        )
    }
}
