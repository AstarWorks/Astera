package com.astarworks.astera.stats.application.eventbus.listeners.flex

import com.astarworks.astera.core.application.profile.InMemoryProfileCardService
import com.astarworks.astera.core.application.profile.RecomputeProfileCardUseCase
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario F.
 *
 * Proves: an [AchievementUnlocked] event triggers a recompute of the
 * player's [com.astarworks.astera.core.domain.profile.PublicProfileCard]
 * via [RecomputeProfileCardUseCase], persisting the result to
 * [com.astarworks.astera.core.application.port.outbound.IProfileCardService].
 */
class ProfileCardRecomputeScenarioTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `AchievementUnlocked recomputes and persists the profile card`() {
        val cards = InMemoryProfileCardService()
        val recompute = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { "Steve" },
        )
        val listener = RecomputeProfileCardOnAchievementUnlockedListener(recompute)

        listener.handle(
            AchievementUnlocked(
                playerId = playerId,
                achievementId = AchievementId("explorer"),
                unlockedAt = Instant.parse("2026-05-30T12:00:00Z"),
            ),
        )

        val card = cards.find(playerId)
        assertThat(card).isNotNull
        assertThat(card!!.displayName).isEqualTo("Steve")
    }

    @Test
    fun `missing display name is a debug-logged no-op (no save)`() {
        val cards = InMemoryProfileCardService()
        val recompute = RecomputeProfileCardUseCase(
            cards = cards,
            displayNameProvider = { null },
        )
        val listener = RecomputeProfileCardOnAchievementUnlockedListener(recompute)

        listener.handle(
            AchievementUnlocked(
                playerId = playerId,
                achievementId = AchievementId("explorer"),
                unlockedAt = Instant.now(),
            ),
        )

        assertThat(cards.find(playerId)).isNull()
    }
}
