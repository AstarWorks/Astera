package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.table.PlayerAchievementsTable
import com.astarworks.astera.stats.application.port.outbound.IAchievementRepository
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import com.astarworks.astera.persistence.adapter.io.ioTransaction
import java.util.concurrent.ConcurrentHashMap

/**
 * Postgres-backed [IAchievementRepository] (V60, ADR-0210).
 *
 * The catalog is content (`content/achievements/`), kept in memory and filled by
 * [registerAll]; only the per-player unlocks are stored. Positive answers are cached
 * (unlocks never revert) so the per-kill milestone probes cost nothing once unlocked.
 */
public class PostgresAchievementRepository(private val db: Database) : IAchievementRepository {

    private val catalog = ConcurrentHashMap<AchievementId, Achievement>()
    private val unlockedCache = ConcurrentHashMap<PlayerId, MutableSet<AchievementId>>()

    override suspend fun find(id: AchievementId): Achievement? = catalog[id]

    override suspend fun list(): List<Achievement> = catalog.values.toList()

    override suspend fun registerAll(achievements: List<Achievement>) {
        achievements.forEach { catalog[it.id] = it }
    }

    override suspend fun unlocked(playerId: PlayerId): Set<AchievementId> {
        val ids = ioTransaction(db) {
            PlayerAchievementsTable.selectAll()
                .where { PlayerAchievementsTable.playerId eq playerId.uuid }
                .map { AchievementId(it[PlayerAchievementsTable.achievementId]) }
        }.toSet()
        cacheFor(playerId).addAll(ids)
        return ids
    }

    override suspend fun isUnlocked(playerId: PlayerId, id: AchievementId): Boolean {
        if (cacheFor(playerId).contains(id)) return true
        val present = ioTransaction(db) {
            PlayerAchievementsTable.selectAll()
                .where {
                    (PlayerAchievementsTable.playerId eq playerId.uuid) and
                        (PlayerAchievementsTable.achievementId eq id.value)
                }
                .any()
        }
        if (present) cacheFor(playerId).add(id)
        return present
    }

    override suspend fun unlock(playerAchievement: PlayerAchievement) {
        ioTransaction(db) {
            PlayerAchievementsTable.insertIgnore {
                it[playerId] = playerAchievement.playerId.uuid
                it[achievementId] = playerAchievement.achievementId.value
                it[unlockedAt] = playerAchievement.unlockedAt
            }
        }
        cacheFor(playerAchievement.playerId).add(playerAchievement.achievementId)
    }

    private fun cacheFor(playerId: PlayerId): MutableSet<AchievementId> =
        unlockedCache.computeIfAbsent(playerId) { ConcurrentHashMap.newKeySet() }
}
