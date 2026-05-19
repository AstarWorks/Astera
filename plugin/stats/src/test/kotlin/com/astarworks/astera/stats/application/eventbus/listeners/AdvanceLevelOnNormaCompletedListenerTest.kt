package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.norma.NormaReward
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.application.usecase.level.GrantXpUseCase
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.stats.domain.level.ConfiguredNormaParameter
import com.astarworks.astera.stats.domain.level.LevelUpMission
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AdvanceLevelOnNormaCompletedListenerTest {

    private lateinit var levelService: InMemoryLevelService
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var grantXp: GrantXpUseCase
    private val player = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        levelService = InMemoryLevelService()
        eventBus = FakeDomainEventBus()
        grantXp = GrantXpUseCase(levelService, eventBus)
    }

    private fun starReward(amount: Long): NormaReward =
        NormaReward(currencyId = "STAR", currencyAmount = amount, xp = 0L)

    private fun killReward(): NormaReward =
        NormaReward(currencyId = null, currencyAmount = 0L, xp = 0L)

    private fun completed(
        normaId: String,
        reward: NormaReward,
    ): MissionCompleted = MissionCompleted(
        playerId = player,
        normaId = NormaId(normaId),
        reward = reward,
    )

    @Test
    fun `advances level once STAR threshold is met at level 1`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = mapOf(
                1 to ConfiguredNormaParameter(LevelUpMission.STAR, 50),
            ),
        )

        // Two completions adding 30 + 25 = 55 stars (≥ 50).
        listener.handle(completed("n1", starReward(30L)))
        assertThat(levelService.currentLevel(player).value).isEqualTo(1)

        listener.handle(completed("n2", starReward(25L)))

        assertThat(levelService.currentLevel(player).value).isEqualTo(2)
        assertThat(eventBus.publishedOfType<LeveledUp>()).hasSize(1)
    }

    @Test
    fun `advances level once KILL threshold is met`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = mapOf(
                1 to ConfiguredNormaParameter(LevelUpMission.KILL, 2),
            ),
        )

        listener.handle(completed("k1", killReward()))
        assertThat(levelService.currentLevel(player).value).isEqualTo(1)

        listener.handle(completed("k2", killReward()))

        assertThat(levelService.currentLevel(player).value).isEqualTo(2)
        assertThat(eventBus.publishedOfType<LeveledUp>()).hasSize(1)
    }

    @Test
    fun `re-firing the same MissionCompleted is idempotent (no double-advance)`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = mapOf(
                1 to ConfiguredNormaParameter(LevelUpMission.STAR, 50),
            ),
        )
        val event = completed("n1", starReward(60L))

        listener.handle(event)
        listener.handle(event)
        listener.handle(event)

        assertThat(levelService.currentLevel(player).value).isEqualTo(2)
        assertThat(eventBus.publishedOfType<LeveledUp>()).hasSize(1)
    }

    @Test
    fun `does not advance when no criterion is configured for the current level`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = emptyMap(),
        )

        listener.handle(completed("n1", starReward(1_000L)))

        assertThat(levelService.currentLevel(player).value).isEqualTo(1)
        assertThat(eventBus.publishedOfType<LeveledUp>()).isEmpty()
    }

    @Test
    fun `does not advance when the criterion's mission type does not match`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = mapOf(
                1 to ConfiguredNormaParameter(LevelUpMission.KILL, 1),
            ),
        )

        // STAR mission while criterion expects KILL.
        listener.handle(completed("n1", starReward(500L)))

        assertThat(levelService.currentLevel(player).value).isEqualTo(1)
        assertThat(eventBus.publishedOfType<LeveledUp>()).isEmpty()
    }

    @Test
    fun `accumulator persists between events and only advances once threshold is crossed`() {
        val listener = AdvanceLevelOnNormaCompletedListener(
            levelService = levelService,
            grantXp = grantXp,
            criterionByLevel = mapOf(
                1 to ConfiguredNormaParameter(LevelUpMission.STAR, 50),
            ),
        )

        listener.handle(completed("a", starReward(10L)))
        listener.handle(completed("b", starReward(10L)))
        listener.handle(completed("c", starReward(10L)))
        listener.handle(completed("d", starReward(10L)))
        assertThat(levelService.currentLevel(player).value).isEqualTo(1)

        listener.handle(completed("e", starReward(10L)))

        assertThat(levelService.currentLevel(player).value).isEqualTo(2)
    }
}
