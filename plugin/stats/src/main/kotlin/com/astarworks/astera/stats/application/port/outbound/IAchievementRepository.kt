package com.astarworks.astera.stats.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement

/**
 * Outbound port: achievement catalog + per-player unlock records (Wave 5e).
 *
 * In-memory only for Wave 5e; a Postgres overlay may land in a later wave
 * once cross-session unlocks need to survive restarts. The catalog
 * ([list]) is loaded from `content/achievements/{name}.yaml` (worker γ defines
 * the YAML loader).
 */
public interface IAchievementRepository {

    // ---- Catalog -------------------------------------------------------
    public fun find(id: AchievementId): Achievement?
    public fun list(): List<Achievement>
    public fun registerAll(achievements: List<Achievement>)

    // ---- Per-player unlocks --------------------------------------------
    public fun unlocked(playerId: PlayerId): Set<AchievementId>
    public fun isUnlocked(playerId: PlayerId, id: AchievementId): Boolean
    public fun unlock(playerAchievement: PlayerAchievement)
}
