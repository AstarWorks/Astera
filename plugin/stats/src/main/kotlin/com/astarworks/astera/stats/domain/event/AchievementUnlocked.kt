package com.astarworks.astera.stats.domain.event

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.achievement.AchievementId
import java.time.Instant

/**
 * Fired exactly once per (player, achievement) pair when a milestone listener
 * (Wave 5e) successfully unlocks an [AchievementId] through
 * `UnlockAchievementUseCase`. Subsequent attempts surface
 * `AchievementError.AlreadyUnlocked` and do NOT re-publish this event.
 *
 * Downstream listeners (lobby HUD, Discord broadcaster, title/badge awarder)
 * subscribe to this event; the publisher does not know about them.
 *
 * @property unlockedAt The clock-wall time the unlock was recorded.
 *   Mirrored to [occurredAt] when constructed by the use case so the two
 *   stay aligned; tests inject a fixed [java.time.Clock] to assert on the
 *   value.
 */
public data class AchievementUnlocked(
    val playerId: PlayerId,
    val achievementId: AchievementId,
    val unlockedAt: Instant,
    override val occurredAt: Instant = unlockedAt,
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Player(playerId.uuid)
}
