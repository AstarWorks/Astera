package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IAchievementRepository] for Wave 5e.
 *
 * Holds the catalog ([Achievement] definitions) and the per-player unlock
 * set. Wave 5f or later may add a Postgres overlay; until then, unlocks do
 * not survive a server restart — acceptable for Phase 1 PoC.
 *
 * Thread-safety: backed by [ConcurrentHashMap]; the per-player set is
 * upgraded to a synchronized set on first write so milestone listeners
 * firing in parallel on the same player do not lose unlocks.
 */
public class InMemoryAchievementRepository : IAchievementRepository {

    private val catalog: ConcurrentHashMap<AchievementId, Achievement> = ConcurrentHashMap()
    private val unlocks: ConcurrentHashMap<PlayerId, MutableSet<AchievementId>> = ConcurrentHashMap()

    // ---- Catalog -----------------------------------------------------------

    override fun find(id: AchievementId): Achievement? = catalog[id]

    override fun list(): List<Achievement> = catalog.values.toList()

    override fun registerAll(achievements: List<Achievement>) {
        for (a in achievements) {
            catalog[a.id] = a
        }
    }

    // ---- Per-player unlocks ------------------------------------------------

    override fun unlocked(playerId: PlayerId): Set<AchievementId> =
        unlocks[playerId]?.toSet() ?: emptySet()

    override fun isUnlocked(playerId: PlayerId, id: AchievementId): Boolean =
        unlocks[playerId]?.contains(id) == true

    override fun unlock(playerAchievement: PlayerAchievement) {
        val set = unlocks.computeIfAbsent(playerAchievement.playerId) {
            java.util.Collections.synchronizedSet(linkedSetOf())
        }
        set.add(playerAchievement.achievementId)
    }
}
