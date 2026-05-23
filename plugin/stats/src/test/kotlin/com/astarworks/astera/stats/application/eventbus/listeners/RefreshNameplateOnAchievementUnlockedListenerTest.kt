package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.nameplate.NameplateBadgeRegistry
import com.astarworks.astera.stats.application.usecase.nameplate.RecomputeNameplateBadgeUseCase
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RefreshNameplateOnAchievementUnlockedListenerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val now = Instant.parse("2026-05-20T00:00:00Z")

    @Test
    fun `unlock recomputes the badge and sets milestone key`() {
        val cache = NameplateBadgeRegistry()
        val recompute = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
            powerIndexTotalProvider = { 250L },
        )
        val listener = RefreshNameplateOnAchievementUnlockedListener(cache, recompute)
        val event = AchievementUnlocked(
            playerId = player,
            achievementId = AchievementId("kills-1000"),
            unlockedAt = now,
        )

        listener.handle(event)

        val badge = cache.find(player)
        assertThat(badge).isNotNull
        assertThat(badge!!.milestoneKey).isEqualTo(MessageKey("astera.stats.milestone.kills-1000"))
        assertThat(badge.powerIndexTotal).isEqualTo(250L)
    }

    @Test
    fun `null milestone from custom resolver leaves badge milestone unchanged`() {
        val cache = NameplateBadgeRegistry()
        val recompute = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
        )
        val listener = RefreshNameplateOnAchievementUnlockedListener(
            cache = cache,
            recompute = recompute,
            milestoneKeyOf = { null },
        )
        listener.handle(
            AchievementUnlocked(
                playerId = player,
                achievementId = AchievementId("trivial"),
                unlockedAt = now,
            ),
        )
        assertThat(cache.find(player)!!.milestoneKey).isNull()
    }

    @Test
    fun `custom resolver controls which milestone key is displayed`() {
        val cache = NameplateBadgeRegistry()
        val recompute = RecomputeNameplateBadgeUseCase(
            cache = cache,
            equippedTitleProvider = { null },
        )
        val resolver: (AchievementUnlocked) -> MessageKey? = {
            if (it.achievementId == AchievementId("boss-defeats-100")) {
                MessageKey("astera.stats.milestone.boss-slayer")
            } else {
                null
            }
        }
        val listener = RefreshNameplateOnAchievementUnlockedListener(cache, recompute, resolver)
        listener.handle(
            AchievementUnlocked(
                playerId = player,
                achievementId = AchievementId("boss-defeats-100"),
                unlockedAt = now,
            ),
        )
        assertThat(cache.find(player)!!.milestoneKey)
            .isEqualTo(MessageKey("astera.stats.milestone.boss-slayer"))
    }
}
