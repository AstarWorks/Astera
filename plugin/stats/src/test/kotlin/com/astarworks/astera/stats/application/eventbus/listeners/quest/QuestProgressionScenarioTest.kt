package com.astarworks.astera.stats.application.eventbus.listeners.quest

import com.astarworks.astera.core.application.contents.QuestChainContents
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.QuestChain
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestStage
import com.astarworks.astera.core.domain.quest.QuestStageId
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave K 5integration-wire (ADR-0128) — scenario E.
 *
 * Proves: when a [QuestStageCompleted] event carries a stage whose
 * `linkedAchievementId` is set, [UpdateAchievementProgressOnQuestStageCompletedListener]
 * unlocks the linked achievement. Stages without a link no-op.
 */
class QuestProgressionScenarioTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val chainId = QuestChainId("forest-exploration")
    private val stageWithLink = QuestStageId("prove-prowess")
    private val stageWithoutLink = QuestStageId("hunt-forest-mobs")
    private val linkedAchievement = AchievementId("explorer")

    @Test
    fun `stage with linkedAchievementId unlocks the achievement`() {
        val bus = FakeDomainEventBus()
        val chains = chainsRegistry()
        val (repo, unlock) = unlockAndRepo(bus)
        val listener = UpdateAchievementProgressOnQuestStageCompletedListener(chains, unlock)

        listener.handle(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = stageWithLink,
                nextStageId = null,
                atTick = 100L,
            ),
        )

        assertThat(repo.isUnlocked(playerId, linkedAchievement)).isTrue()
        val events = bus.publishedOfType<AchievementUnlocked>()
        assertThat(events).hasSize(1)
        assertThat(events[0].achievementId).isEqualTo(linkedAchievement)
    }

    @Test
    fun `stage without linkedAchievementId is a no-op`() {
        val bus = FakeDomainEventBus()
        val chains = chainsRegistry()
        val (repo, unlock) = unlockAndRepo(bus)
        val listener = UpdateAchievementProgressOnQuestStageCompletedListener(chains, unlock)

        listener.handle(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = stageWithoutLink,
                nextStageId = stageWithLink,
                atTick = 50L,
            ),
        )

        assertThat(repo.isUnlocked(playerId, linkedAchievement)).isFalse()
        assertThat(bus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `unknown chain is a debug-logged no-op`() {
        val bus = FakeDomainEventBus()
        val chains = QuestChainContents() // intentionally empty
        val (_, unlock) = unlockAndRepo(bus)
        val listener = UpdateAchievementProgressOnQuestStageCompletedListener(chains, unlock)

        listener.handle(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = stageWithLink,
                nextStageId = null,
                atTick = 0L,
            ),
        )

        assertThat(bus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    private fun chainsRegistry(): QuestChainContents = QuestChainContents().apply {
        register(
            QuestChain(
                id = chainId,
                titleKey = MessageKey("astera.quest.forest-exploration.title"),
                stages = listOf(
                    QuestStage(
                        id = stageWithoutLink,
                        titleKey = MessageKey("astera.quest.forest-exploration.hunt-forest-mobs.title"),
                        descriptionKey = MessageKey("astera.quest.forest-exploration.hunt-forest-mobs.description"),
                        criteria = AdvancementCriteria.KillCount(target = 10),
                        rewards = emptyList(),
                        followingStageId = stageWithLink,
                        linkedAchievementId = null,
                    ),
                    QuestStage(
                        id = stageWithLink,
                        titleKey = MessageKey("astera.quest.forest-exploration.prove-prowess.title"),
                        descriptionKey = MessageKey("astera.quest.forest-exploration.prove-prowess.description"),
                        criteria = AdvancementCriteria.MatchesWon(count = 1),
                        rewards = emptyList(),
                        followingStageId = null,
                        linkedAchievementId = linkedAchievement,
                    ),
                ),
            ),
        )
    }

    private fun unlockAndRepo(bus: FakeDomainEventBus): Pair<InMemoryAchievementRepository, UnlockAchievementUseCase> {
        val repo = InMemoryAchievementRepository()
        repo.registerAll(
            listOf(
                Achievement(
                    id = linkedAchievement,
                    nameKey = MessageKey("astera.advancement.explorer.title"),
                    descriptionKey = MessageKey("astera.advancement.explorer.description"),
                    iconItemId = "example-sword",
                ),
            ),
        )
        return repo to UnlockAchievementUseCase(repo, bus)
    }
}
