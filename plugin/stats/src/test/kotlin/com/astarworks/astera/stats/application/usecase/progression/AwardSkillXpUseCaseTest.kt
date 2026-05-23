package com.astarworks.astera.stats.application.usecase.progression

import com.astarworks.astera.core.domain.event.SkillLevelUp
import com.astarworks.astera.core.domain.event.SkillXpGained
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.progression.SkillKind
import com.astarworks.astera.core.domain.progression.SkillXpCurve
import com.astarworks.astera.stats.application.service.progression.InMemorySkillProgressionRepository
import com.astarworks.astera.stats.application.service.progression.InMemorySkillXpCurveRegistry
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wave 5multi-skill (ADR-0101) — single-axis XP flow + event publication.
 */
class AwardSkillXpUseCaseTest {

    private lateinit var repo: InMemorySkillProgressionRepository
    private lateinit var curves: InMemorySkillXpCurveRegistry
    private lateinit var bus: FakeDomainEventBus
    private lateinit var useCase: AwardSkillXpUseCase
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemorySkillProgressionRepository()
        // Use a tiny custom curve so unit tests don't depend on the exponential defaults.
        curves = InMemorySkillXpCurveRegistry(defaultCurve = SkillXpCurve(thresholds = listOf(100L, 250L, 500L)))
        bus = FakeDomainEventBus()
        useCase = AwardSkillXpUseCase(repo, curves, bus)
    }

    @Test
    fun `zero amount is a no-op`() {
        val result = useCase.execute(playerId, SkillKind.Combat, 0L)
        assertThat(result).isNull()
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `negative amount is a no-op`() {
        val result = useCase.execute(playerId, SkillKind.Combat, -10L)
        assertThat(result).isNull()
        assertThat(bus.published).isEmpty()
    }

    @Test
    fun `first XP gain creates the row at level 1`() {
        val result = useCase.execute(playerId, SkillKind.Combat, 50L)
        assertThat(result).isNotNull
        assertThat(result!!.xp).isEqualTo(50L)
        assertThat(result.level).isEqualTo(1)
        val gained = bus.publishedOfType<SkillXpGained>()
        assertThat(gained).hasSize(1)
        assertThat(gained.first().kind).isEqualTo(SkillKind.Combat)
        assertThat(gained.first().amount).isEqualTo(50L)
        assertThat(gained.first().newTotal).isEqualTo(50L)
        assertThat(bus.publishedOfType<SkillLevelUp>()).isEmpty()
    }

    @Test
    fun `crossing a threshold publishes SkillLevelUp`() {
        useCase.execute(playerId, SkillKind.Combat, 50L)
        bus.published.clear()
        useCase.execute(playerId, SkillKind.Combat, 60L) // 50 + 60 = 110 → Lv 2

        val levelUps = bus.publishedOfType<SkillLevelUp>()
        assertThat(levelUps).hasSize(1)
        assertThat(levelUps.first().oldLevel).isEqualTo(1)
        assertThat(levelUps.first().newLevel).isEqualTo(2)
        assertThat(repo.find(playerId, SkillKind.Combat)!!.level).isEqualTo(2)
    }

    @Test
    fun `multi-level jump publishes a single SkillLevelUp with span`() {
        val result = useCase.execute(playerId, SkillKind.Mining, 600L) // skips Lv 2 + 3 → Lv 4

        val levelUps = bus.publishedOfType<SkillLevelUp>()
        assertThat(levelUps).hasSize(1)
        assertThat(levelUps.first().oldLevel).isEqualTo(1)
        assertThat(levelUps.first().newLevel).isEqualTo(4)
        assertThat(result!!.level).isEqualTo(4)
    }

    @Test
    fun `axes are independent per player`() {
        useCase.execute(playerId, SkillKind.Combat, 50L)
        useCase.execute(playerId, SkillKind.Mining, 30L)

        assertThat(repo.find(playerId, SkillKind.Combat)!!.xp).isEqualTo(50L)
        assertThat(repo.find(playerId, SkillKind.Mining)!!.xp).isEqualTo(30L)
        assertThat(repo.allFor(playerId)).hasSize(2)
    }
}
