package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryAchievementRepositoryTest {

    private lateinit var repo: InMemoryAchievementRepository
    private val player = PlayerId(UUID.randomUUID())
    private val other = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryAchievementRepository()
    }

    private fun achievement(id: String): Achievement = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey("astera.stats.achievement.$id.name"),
        descriptionKey = MessageKey("astera.stats.achievement.$id.description"),
        iconItemId = "minecraft:iron_sword",
    )

    @Test
    fun `registerAll adds catalog entries findable by id`() {
        val a = achievement("kills-10")
        val b = achievement("damage-1k")
        repo.registerAll(listOf(a, b))

        assertThat(repo.find(AchievementId("kills-10"))).isEqualTo(a)
        assertThat(repo.find(AchievementId("damage-1k"))).isEqualTo(b)
        assertThat(repo.list()).containsExactlyInAnyOrder(a, b)
    }

    @Test
    fun `registerAll is idempotent and overwrites entries with the same id`() {
        val v1 = achievement("kills-10")
        val v2 = v1.copy(iconItemId = "minecraft:diamond_sword")

        repo.registerAll(listOf(v1))
        repo.registerAll(listOf(v2))

        assertThat(repo.find(AchievementId("kills-10"))).isEqualTo(v2)
        assertThat(repo.list()).containsExactly(v2)
    }

    @Test
    fun `unlock records achievement and unlocked enumerates per-player`() {
        val pa = PlayerAchievement(player, AchievementId("kills-10"), Instant.ofEpochSecond(100))
        repo.unlock(pa)

        assertThat(repo.isUnlocked(player, AchievementId("kills-10"))).isTrue()
        assertThat(repo.unlocked(player)).containsExactly(AchievementId("kills-10"))
        assertThat(repo.unlocked(other)).isEmpty()
    }

    @Test
    fun `unlock is idempotent for the same achievement id (no duplicate)`() {
        val pa1 = PlayerAchievement(player, AchievementId("kills-10"), Instant.ofEpochSecond(100))
        val pa2 = PlayerAchievement(player, AchievementId("kills-10"), Instant.ofEpochSecond(200))
        repo.unlock(pa1)
        repo.unlock(pa2)

        assertThat(repo.unlocked(player)).containsExactly(AchievementId("kills-10"))
    }

    @Test
    fun `unlocked returns a snapshot that does not back-reference internal storage`() {
        val pa = PlayerAchievement(player, AchievementId("kills-10"), Instant.ofEpochSecond(100))
        repo.unlock(pa)

        val snapshot = repo.unlocked(player)
        repo.unlock(PlayerAchievement(player, AchievementId("damage-1k"), Instant.ofEpochSecond(200)))

        // snapshot must not pick up the later unlock
        assertThat(snapshot).containsExactly(AchievementId("kills-10"))
    }

    @Test
    fun `isUnlocked is false for unknown id and unknown player`() {
        assertThat(repo.isUnlocked(player, AchievementId("kills-10"))).isFalse()
        assertThat(repo.unlocked(other)).isEmpty()
    }
}
