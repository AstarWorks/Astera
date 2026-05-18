package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.stats.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class UnlockAchievementOnMatchWinListenerTest {

    private lateinit var statsRepo: InMemoryPlayerStatsRepository
    private lateinit var achRepo: InMemoryAchievementRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: UnlockAchievementOnMatchWinListener
    private val rookie = PlayerId(UUID.randomUUID())
    private val veteran = PlayerId(UUID.randomUUID())
    private val legend = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        statsRepo = InMemoryPlayerStatsRepository()
        achRepo = InMemoryAchievementRepository()
        eventBus = FakeDomainEventBus()
        achRepo.registerAll(
            listOf(
                catalogEntry("wins-1"),
                catalogEntry("wins-10"),
                catalogEntry("wins-50"),
            ),
        )
        val useCase = UnlockAchievementUseCase(achRepo, eventBus)
        listener = UnlockAchievementOnMatchWinListener(useCase, statsRepo)
    }

    private fun catalogEntry(id: String) = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey("astera.stats.achievement.$id.name"),
        descriptionKey = MessageKey("astera.stats.achievement.$id.description"),
        iconItemId = "minecraft:gold_block",
    )

    private fun seedWins(player: PlayerId, wins: Long) {
        statsRepo.upsert(PlayerStats.of(player, wins = wins))
    }

    private fun matchEndedEvent() = MatchEnded(
        matchId = MatchId("m-1"),
        winningTeam = null,
        atTick = 100L,
        reason = EndReason.TimerExpired,
    )

    @Test
    fun `1 win unlocks wins-1 only`() {
        seedWins(rookie, 1L)
        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(rookie)).containsExactly(AchievementId("wins-1"))
    }

    @Test
    fun `10 wins unlocks wins-1 and wins-10`() {
        seedWins(veteran, 10L)
        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(veteran)).containsExactlyInAnyOrder(
            AchievementId("wins-1"),
            AchievementId("wins-10"),
        )
    }

    @Test
    fun `50 wins unlocks all three thresholds`() {
        seedWins(legend, 50L)
        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(legend)).containsExactlyInAnyOrder(
            AchievementId("wins-1"),
            AchievementId("wins-10"),
            AchievementId("wins-50"),
        )
    }

    @Test
    fun `mixed-player population credits each player independently`() {
        seedWins(rookie, 1L)
        seedWins(veteran, 10L)
        seedWins(legend, 50L)

        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(rookie)).containsExactly(AchievementId("wins-1"))
        assertThat(achRepo.unlocked(veteran)).containsExactlyInAnyOrder(
            AchievementId("wins-1"),
            AchievementId("wins-10"),
        )
        assertThat(achRepo.unlocked(legend)).hasSize(3)
    }

    @Test
    fun `subsequent match ended events do not duplicate already-unlocked entries`() {
        seedWins(legend, 50L)
        listener.handle(matchEndedEvent())
        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(legend)).hasSize(3)
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).hasSize(3)
    }

    @Test
    fun `empty stats repo yields no work`() {
        listener.handle(matchEndedEvent())

        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `player with 0 wins triggers no unlock`() {
        seedWins(rookie, 0L)
        listener.handle(matchEndedEvent())

        assertThat(achRepo.unlocked(rookie)).isEmpty()
    }
}
