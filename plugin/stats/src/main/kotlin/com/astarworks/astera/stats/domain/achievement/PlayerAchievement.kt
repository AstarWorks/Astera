package com.astarworks.astera.stats.domain.achievement

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Record of a player having unlocked an achievement (Wave 5e).
 *
 * Worker γ persists via `IAchievementRepository`. Wave 5e uses in-memory
 * storage; Wave 5f or later may add a Postgres table.
 */
public data class PlayerAchievement(
    val playerId: PlayerId,
    val achievementId: AchievementId,
    val unlockedAt: Instant,
)
