package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
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

class UnlockAchievementOnKillMilestoneListenerTest {

    private lateinit var statsRepo: InMemoryPlayerStatsRepository
    private lateinit var achRepo: InMemoryAchievementRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: UnlockAchievementOnKillMilestoneListener
    private val killer = PlayerId(UUID.randomUUID())
    private val victim = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        statsRepo = InMemoryPlayerStatsRepository()
        achRepo = InMemoryAchievementRepository()
        eventBus = FakeDomainEventBus()
        achRepo.registerAll(
            listOf(
                catalogEntry("kills-10"),
                catalogEntry("kills-50"),
                catalogEntry("kills-100"),
            ),
        )
        val useCase = UnlockAchievementUseCase(achRepo, eventBus)
        listener = UnlockAchievementOnKillMilestoneListener(useCase, statsRepo)
    }

    private fun catalogEntry(id: String) = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey("astera.stats.achievement.$id.name"),
        descriptionKey = MessageKey("astera.stats.achievement.$id.description"),
        iconItemId = "minecraft:iron_sword",
    )

    private fun seedKills(count: Long) {
        statsRepo.upsert(PlayerStats.of(killer, kills = count))
    }

    private fun killEvent(k: PlayerId? = killer) = PlayerKilled(
        victim = victim,
        killer = k,
        source = DamageSource.Environment,
        atTick = 1L,
    )

    @Test
    fun `kills below 10 unlocks no achievement`() {
        seedKills(9L)
        listener.handle(killEvent())

        assertThat(achRepo.unlocked(killer)).isEmpty()
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `kills at exactly 10 unlocks kills-10`() {
        seedKills(10L)
        listener.handle(killEvent())

        assertThat(achRepo.unlocked(killer)).containsExactly(AchievementId("kills-10"))
    }

    @Test
    fun `kills at 50 unlocks kills-10 and kills-50 idempotently after multiple events`() {
        seedKills(50L)
        listener.handle(killEvent())
        listener.handle(killEvent()) // second probe must not duplicate

        assertThat(achRepo.unlocked(killer)).containsExactlyInAnyOrder(
            AchievementId("kills-10"),
            AchievementId("kills-50"),
        )
        // Each unlock published exactly once
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).hasSize(2)
    }

    @Test
    fun `kills at 100 unlocks all three thresholds`() {
        seedKills(100L)
        listener.handle(killEvent())

        assertThat(achRepo.unlocked(killer)).containsExactlyInAnyOrder(
            AchievementId("kills-10"),
            AchievementId("kills-50"),
            AchievementId("kills-100"),
        )
    }

    @Test
    fun `null killer is a no-op`() {
        seedKills(100L)
        listener.handle(killEvent(k = null))

        assertThat(achRepo.unlocked(killer)).isEmpty()
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `missing PlayerStats entry (unrecorded killer) is a no-op`() {
        // Do NOT seed killer stats
        listener.handle(killEvent())

        assertThat(achRepo.unlocked(killer)).isEmpty()
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `uncatalogued achievement is tolerated silently`() {
        // Drop the catalog entirely; threshold should fire NotInCatalog, swallowed
        val emptyAchRepo = InMemoryAchievementRepository()
        val freshBus = FakeDomainEventBus()
        val useCase = UnlockAchievementUseCase(emptyAchRepo, freshBus)
        val freshListener = UnlockAchievementOnKillMilestoneListener(useCase, statsRepo)
        seedKills(100L)

        freshListener.handle(killEvent())

        assertThat(emptyAchRepo.unlocked(killer)).isEmpty()
        assertThat(freshBus.published).isEmpty()
    }
}
