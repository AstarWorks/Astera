package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.damage.DamageSource
import com.astarworks.astera.combat.domain.damage.ResolvedDamage
import com.astarworks.astera.combat.domain.event.DamageDealt
import com.astarworks.astera.core.domain.damage.DamageAttribute
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.achievement.UnlockAchievementUseCase
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class UnlockAchievementOnDamageMilestoneListenerTest {

    private lateinit var statsRepo: InMemoryPlayerStatsRepository
    private lateinit var achRepo: InMemoryAchievementRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: UnlockAchievementOnDamageMilestoneListener
    private val attacker = PlayerId(UUID.randomUUID())
    private val target = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        statsRepo = InMemoryPlayerStatsRepository()
        achRepo = InMemoryAchievementRepository()
        eventBus = FakeDomainEventBus()
        achRepo.registerAll(
            listOf(
                catalogEntry("damage-1k"),
                catalogEntry("damage-10k"),
            ),
        )
        val useCase = UnlockAchievementUseCase(achRepo, eventBus)
        listener = UnlockAchievementOnDamageMilestoneListener(useCase, statsRepo)
    }

    private fun catalogEntry(id: String) = Achievement(
        id = AchievementId(id),
        nameKey = MessageKey("astera.stats.achievement.$id.name"),
        descriptionKey = MessageKey("astera.stats.achievement.$id.description"),
        iconItemId = "minecraft:iron_sword",
    )

    private fun seedDamage(total: Long) {
        statsRepo.upsert(PlayerStats.of(attacker, damageDealt = total))
    }

    private fun damageEvent(a: PlayerId? = attacker) = DamageDealt(
        attacker = a,
        target = target,
        resolved = ResolvedDamage(
            finalAmount = 5.0,
            attribute = DamageAttribute.PHYSICAL,
            wasBlocked = false,
            wasCritical = false,
        ),
        source = DamageSource.Environment.Generic,
        atTick = 1L,
    )

    @Test
    fun `damage below 1k unlocks nothing`() {
        seedDamage(999L)
        listener.handle(damageEvent())

        assertThat(achRepo.unlocked(attacker)).isEmpty()
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `damage at exactly 1000 unlocks damage-1k`() {
        seedDamage(1_000L)
        listener.handle(damageEvent())

        assertThat(achRepo.unlocked(attacker)).containsExactly(AchievementId("damage-1k"))
    }

    @Test
    fun `damage at 10000 unlocks both thresholds`() {
        seedDamage(10_000L)
        listener.handle(damageEvent())

        assertThat(achRepo.unlocked(attacker)).containsExactlyInAnyOrder(
            AchievementId("damage-1k"),
            AchievementId("damage-10k"),
        )
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).hasSize(2)
    }

    @Test
    fun `repeat events after thresholds reached do not duplicate unlocks`() {
        seedDamage(10_000L)
        listener.handle(damageEvent())
        listener.handle(damageEvent())
        listener.handle(damageEvent())

        assertThat(achRepo.unlocked(attacker)).hasSize(2)
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).hasSize(2)
    }

    @Test
    fun `null attacker is a no-op`() {
        seedDamage(10_000L)
        listener.handle(damageEvent(a = null))

        assertThat(achRepo.unlocked(attacker)).isEmpty()
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).isEmpty()
    }

    @Test
    fun `missing PlayerStats is a no-op`() {
        listener.handle(damageEvent())

        assertThat(achRepo.unlocked(attacker)).isEmpty()
    }
}
