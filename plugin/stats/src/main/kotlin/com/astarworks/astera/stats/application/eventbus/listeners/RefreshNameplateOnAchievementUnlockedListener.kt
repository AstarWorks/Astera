package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry
import com.astarworks.astera.stats.application.usecase.nameplate.RecomputeNameplateBadgeUseCase
import com.astarworks.astera.stats.domain.event.AchievementUnlocked

/**
 * Refresh the player's [com.astarworks.astera.stats.domain.nameplate.NameplateBadge]
 * whenever an [AchievementUnlocked] event fires (Wave 5flex / ADR-0102
 * surface #2 + #5).
 *
 * Two effects:
 *
 *   1. The unlocked achievement id is recorded as the player's current
 *      *milestone* in the nameplate cache. The match-nameplate renderer
 *      reads the badge's `milestoneKey` and surfaces the latest unlock
 *      for ~30 seconds (Phase C wiring uses a TTL — Wave 5flex puts the
 *      milestone in the badge directly; expiry policy lands in a later
 *      wave).
 *   2. The badge is recomputed so a new title (e.g. Achievement →
 *      auto-grant Title) is reflected immediately.
 *
 * # Idempotency
 *
 * Re-running with the same event yields the same badge; the cache
 * upsert is harmless.
 */
public class RefreshNameplateOnAchievementUnlockedListener(
    private val cache: NameplateBadgeRegistry,
    private val recompute: RecomputeNameplateBadgeUseCase,
    private val milestoneKeyOf: (AchievementUnlocked) -> MessageKey? = ::defaultMilestoneKey,
) {

    public fun handle(event: AchievementUnlocked) {
        val updated = recompute.recompute(event.playerId)
        val milestone = milestoneKeyOf(event)
        if (milestone != null && updated.milestoneKey != milestone) {
            cache.put(updated.copy(milestoneKey = milestone))
        }
    }

    public companion object {
        /**
         * Default milestone key: prefix the achievement id with
         * `astera.stats.milestone.`. Phase C may override with a
         * curated list (e.g. only `kills-1000` + `boss-defeats-100`
         * qualify as match-visible milestones).
         */
        public fun defaultMilestoneKey(event: AchievementUnlocked): MessageKey =
            MessageKey("astera.stats.milestone.${event.achievementId.value}")
    }
}
