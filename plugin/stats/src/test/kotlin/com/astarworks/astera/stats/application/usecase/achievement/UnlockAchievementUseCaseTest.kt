package com.astarworks.astera.stats.application.usecase.achievement

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryAchievementRepository
import com.astarworks.astera.stats.domain.achievement.Achievement
import com.astarworks.astera.core.domain.achievement.AchievementId
import com.astarworks.astera.stats.domain.event.AchievementUnlocked
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UnlockAchievementUseCaseTest {

    private lateinit var repo: InMemoryAchievementRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: UnlockAchievementUseCase
    private val player = PlayerId(UUID.randomUUID())
    private val achievementId = AchievementId("kills-10")
    private val fixedInstant = Instant.parse("2026-05-19T12:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private val catalogEntry = Achievement(
        id = achievementId,
        nameKey = MessageKey("astera.stats.achievement.kills-10.name"),
        descriptionKey = MessageKey("astera.stats.achievement.kills-10.description"),
        iconItemId = "minecraft:iron_sword",
    )

    @BeforeEach
    fun setUp() {
        repo = InMemoryAchievementRepository()
        eventBus = FakeDomainEventBus()
        useCase = UnlockAchievementUseCase(repo, eventBus, clock)
    }

    @Test
    fun `unlock succeeds for catalogued id and stamps PlayerAchievement at clock instant`() {
        repo.registerAll(listOf(catalogEntry))

        val result = useCase.unlock(player, achievementId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val pa = (result as Result.Success).value
        assertThat(pa.playerId).isEqualTo(player)
        assertThat(pa.achievementId).isEqualTo(achievementId)
        assertThat(pa.unlockedAt).isEqualTo(fixedInstant)
    }

    @Test
    fun `unlock persists into the repository`() {
        repo.registerAll(listOf(catalogEntry))

        useCase.unlock(player, achievementId)

        assertThat(repo.isUnlocked(player, achievementId)).isTrue()
        assertThat(repo.unlocked(player)).containsExactly(achievementId)
    }

    @Test
    fun `unlock publishes AchievementUnlocked event with matching fields`() {
        repo.registerAll(listOf(catalogEntry))

        useCase.unlock(player, achievementId)

        val events = eventBus.publishedOfType<AchievementUnlocked>()
        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.playerId).isEqualTo(player)
        assertThat(event.achievementId).isEqualTo(achievementId)
        assertThat(event.unlockedAt).isEqualTo(fixedInstant)
        assertThat(event.occurredAt).isEqualTo(fixedInstant)
        assertThat(event.version).isEqualTo(1)
    }

    @Test
    fun `unlock returns NotInCatalog when id is unknown and publishes nothing`() {
        val result = useCase.unlock(player, AchievementId("kills-10"))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val err = (result as Result.Failure).error
        assertThat(err).isInstanceOf(AchievementError.NotInCatalog::class.java)
        assertThat((err as AchievementError.NotInCatalog).id).isEqualTo(AchievementId("kills-10"))
        assertThat(eventBus.published).isEmpty()
        assertThat(repo.isUnlocked(player, AchievementId("kills-10"))).isFalse()
    }

    @Test
    fun `unlock returns AlreadyUnlocked on second call and publishes only once`() {
        repo.registerAll(listOf(catalogEntry))
        useCase.unlock(player, achievementId)

        val second = useCase.unlock(player, achievementId)

        assertThat(second).isInstanceOf(Result.Failure::class.java)
        val err = (second as Result.Failure).error
        assertThat(err).isInstanceOf(AchievementError.AlreadyUnlocked::class.java)
        assertThat(eventBus.publishedOfType<AchievementUnlocked>()).hasSize(1)
    }

    @Test
    fun `AchievementError variants expose distinct messageKeys`() {
        val notInCatalog = AchievementError.NotInCatalog(achievementId).messageKey.value
        val alreadyUnlocked = AchievementError.AlreadyUnlocked(achievementId).messageKey.value
        assertThat(notInCatalog).isNotEqualTo(alreadyUnlocked)
        assertThat(notInCatalog).startsWith("astera.stats.achievement.error.")
        assertThat(alreadyUnlocked).startsWith("astera.stats.achievement.error.")
    }
}
