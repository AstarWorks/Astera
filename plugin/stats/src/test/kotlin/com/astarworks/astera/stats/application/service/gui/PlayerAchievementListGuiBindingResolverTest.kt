package com.astarworks.astera.stats.application.service.gui

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.gui.BindingKey
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.achievement.PlayerAchievement
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PlayerAchievementListGuiBindingResolverTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    private fun achievement(id: String): Achievement = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey("astera.achievement.$id.name"),
        descriptionKey = MessageKey("astera.achievement.$id.desc"),
        iconItemId = "icon-$id",
    )

    @Test
    fun `unlocked achievement renders unlocked tile with name override`() {
        // InMemoryAchievementRepository stores catalog in a ConcurrentHashMap
        // whose iteration order is undefined. Register only one achievement so
        // the test asserts the unlocked-tile branch deterministically.
        val viewer = pid()
        val repo = InMemoryAchievementRepository().apply {
            registerAll(listOf(achievement("first-blood")))
            unlock(PlayerAchievement(viewer, AchievementId("first-blood"), Instant.now()))
        }
        val resolver = PlayerAchievementListGuiBindingResolver(repo)

        val resolved = resolver.resolve(BindingKey("player.achievement.list.0"), viewer)

        assertThat(resolved).isNotNull
        assertThat(resolved!!.itemId.value).isEqualTo(PlayerAchievementListGuiBindingResolver.TILE_UNLOCKED)
        assertThat(resolved.displayOverride).isEqualTo(MessageKey("astera.achievement.first-blood.name"))
    }

    @Test
    fun `locked achievement renders locked tile`() {
        val viewer = pid()
        val repo = InMemoryAchievementRepository().apply {
            registerAll(listOf(achievement("first-blood")))
            // No unlock — viewer has no progress.
        }
        val resolver = PlayerAchievementListGuiBindingResolver(repo)

        val resolved = resolver.resolve(BindingKey("player.achievement.list.0"), viewer)

        assertThat(resolved!!.itemId.value).isEqualTo(PlayerAchievementListGuiBindingResolver.TILE_LOCKED)
    }

    @Test
    fun `out-of-bounds index returns null`() {
        val viewer = pid()
        val repo = InMemoryAchievementRepository().apply {
            registerAll(listOf(achievement("first-blood")))
        }
        val resolver = PlayerAchievementListGuiBindingResolver(repo)

        assertThat(resolver.resolve(BindingKey("player.achievement.list.5"), viewer)).isNull()
    }

    @Test
    fun `non-matching prefix returns null`() {
        val repo = InMemoryAchievementRepository()
        val resolver = PlayerAchievementListGuiBindingResolver(repo)
        assertThat(resolver.resolve(BindingKey("player.kills"), pid())).isNull()
    }

    @Test
    fun `provider null returns null`() {
        val resolver = PlayerAchievementListGuiBindingResolver { null }
        assertThat(resolver.resolve(BindingKey("player.achievement.list.0"), pid())).isNull()
    }

    @Test
    fun `null viewer returns null`() {
        val repo = InMemoryAchievementRepository().apply { registerAll(listOf(achievement("a"))) }
        val resolver = PlayerAchievementListGuiBindingResolver(repo)
        assertThat(resolver.resolve(BindingKey("player.achievement.list.0"), null)).isNull()
    }
}
