package com.astarworks.astera.stats.application.eventbus.listeners.flex

import com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import org.slf4j.LoggerFactory

/**
 * On [AchievementUnlocked] trigger a recompute of the player's
 * [com.astarworks.astera.core.domain.profile.PublicProfileCard]
 * (Wave K 5integration-wire, ADR-0128).
 *
 * # Why a separate listener (and not piggyback on the nameplate one)
 *
 * `RefreshNameplateOnAchievementUnlockedListener` (Wave 5flex) handles
 * the *in-match nameplate cache* — short-lived (~30s milestone display)
 * + match-bound. The profile card is the *long-lived public profile*
 * surface (`/profile <name>` lookups, web roster, Discord embed):
 * different cache, different recompute cost, different invalidation
 * cadence. Splitting them keeps each listener focused on its surface
 * — Wave 5flex's listener can stay TTL-aware without learning how to
 * recompute equipment + titles + cosmetics + power-index from scratch
 * every time.
 *
 * Both listeners subscribe to the same event independently and do not
 * coordinate. The order they fire in is determined by their registration
 * order in `AsteraStatsPlugin`; either ordering produces the same final
 * state (nameplate badge + profile card are independent caches).
 *
 * # Failure handling
 *
 * `RecomputeProfileCardUseCase.recompute` returns `null` when the player
 * has no display name (e.g. an unbound `PlayerId` from a synthetic test
 * publish). That's a no-op for us, not an error — debug-logged.
 *
 * # Why stats, not core
 *
 * `AchievementUnlocked` is owned by `:plugin:stats` (per
 * `stats.domain.event`), so `:plugin:core` cannot subscribe to it
 * directly per ADR-0022 sibling isolation. `RecomputeProfileCardUseCase`
 * lives in Core but is invokable from any sibling that resolves it
 * through the registry, so Stats hosts the wiring.
 */
public class RecomputeProfileCardOnAchievementUnlockedListener(
    private val recompute: RecomputeProfileCardUseCase,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(event: AchievementUnlocked) {
        val card = recompute.recompute(event.playerId)
        if (card == null) {
            log.debug(
                "Profile card recompute skipped (no display name) on AchievementUnlocked: " +
                    "player={} achievement={}",
                event.playerId, event.achievementId,
            )
        } else {
            log.debug(
                "Profile card recomputed: player={} achievement={} powerIndex={}",
                event.playerId, event.achievementId, card.powerIndexTotal,
            )
        }
    }
}
