package com.astarworks.astera.stats.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.event.TutorialCompleted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialScriptId
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario D (achievement half).
 *
 * Proves: `TutorialCompleted` → `tutorial-graduate` achievement unlocked
 * + `AchievementUnlocked` published. Pins the onboarding-loop closer.
 *
 * The reward half (STAR credit) lives in
 * [com.astarworks.astera.economy.application.eventbus.listeners.onboarding.GrantRewardOnTutorialCompletedListenerTest]
 * because `DepositUseCase` is in `:plugin:economy`.
 */
class TutorialCompletionScenarioTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val tutorialId = TutorialScriptId("astera-first-steps")
    private val achievementId = GrantAchievementOnTutorialCompletedListener.TUTORIAL_GRADUATE_ID

    @Test
    fun `TutorialCompleted unlocks the tutorial-graduate achievement`() {
        val bus = FakeDomainEventBus()
        val repo = InMemoryAchievementRepository()
        // Catalog the achievement (production: AchievementContents loader; here: inline).
        repo.registerAll(
            listOf(
                Achievement(
                    id = achievementId,
                    nameKey = MessageKey("astera.advancement.tutorial_graduate.title"),
                    descriptionKey = MessageKey("astera.advancement.tutorial_graduate.description"),
                    iconItemId = "example-sword",
                ),
            ),
        )
        val unlock = UnlockAchievementUseCase(repo, bus)
        val listener = GrantAchievementOnTutorialCompletedListener(unlock)

        // Act
        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))

        // Assert — unlock recorded + event published
        assertThat(repo.isUnlocked(playerId, achievementId)).isTrue()
        val events = bus.publishedOfType<AchievementUnlocked>()
        assertThat(events).hasSize(1)
        assertThat(events[0].playerId).isEqualTo(playerId)
        assertThat(events[0].achievementId).isEqualTo(achievementId)
    }

    @Test
    fun `duplicate tutorial completion does not republish AchievementUnlocked`() {
        val bus = FakeDomainEventBus()
        val repo = InMemoryAchievementRepository()
        repo.registerAll(
            listOf(
                Achievement(
                    id = achievementId,
                    nameKey = MessageKey("astera.advancement.tutorial_graduate.title"),
                    descriptionKey = MessageKey("astera.advancement.tutorial_graduate.description"),
                    iconItemId = "example-sword",
                ),
            ),
        )
        val unlock = UnlockAchievementUseCase(repo, bus)
        val listener = GrantAchievementOnTutorialCompletedListener(unlock)

        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))
        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))

        // Achievement is intrinsically idempotent — second handle stays silent.
        assertThat(bus.publishedOfType<AchievementUnlocked>()).hasSize(1)
    }

    @Test
    fun `NotInCatalog is logged but does not throw`() {
        val bus = FakeDomainEventBus()
        val repo = InMemoryAchievementRepository()
        // Intentionally NOT cataloging — simulates operator missing the YAML.
        val unlock = UnlockAchievementUseCase(repo, bus)
        val listener = GrantAchievementOnTutorialCompletedListener(
            unlockAchievement = unlock,
            achievementId = AchievementId("tutorial-graduate"),
        )

        listener.handle(TutorialCompleted(playerId = playerId, tutorialId = tutorialId))

        assertThat(bus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }
}
