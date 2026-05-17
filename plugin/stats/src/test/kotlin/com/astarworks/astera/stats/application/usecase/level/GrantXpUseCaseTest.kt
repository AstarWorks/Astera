package com.astarworks.astera.stats.application.usecase.level

import com.astarworks.astera.core.domain.event.XpSource
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.service.InMemoryLevelService
import com.astarworks.astera.stats.domain.event.LeveledUp
import com.astarworks.astera.stats.domain.event.XpGranted
import com.astarworks.astera.stats.domain.level.Level
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class GrantXpUseCaseTest {

    private lateinit var levelService: InMemoryLevelService
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var useCase: GrantXpUseCase
    private val playerId = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        levelService = InMemoryLevelService()
        eventBus = FakeDomainEventBus()
        useCase = GrantXpUseCase(levelService, eventBus)
    }

    @Test
    fun `execute returns failure for zero amount`() {
        val result = useCase.execute(playerId, 0L, XpSource.Kill)
        assertThat(result.isFailure).isTrue()
        assertThat(result.errorOrNull()).isInstanceOf(GrantXpError.InvalidAmount::class.java)
    }

    @Test
    fun `execute returns failure for negative amount`() {
        val result = useCase.execute(playerId, -10L, XpSource.Kill)
        assertThat(result.isFailure).isTrue()
        val error = result.errorOrNull() as GrantXpError.InvalidAmount
        assertThat(error.amount).isEqualTo(-10L)
    }

    @Test
    fun `execute publishes XpGranted event on success`() {
        useCase.execute(playerId, 50L, XpSource.Kill)
        val events = eventBus.publishedOfType<XpGranted>()
        assertThat(events).hasSize(1)
        assertThat(events.first().playerId).isEqualTo(playerId)
        assertThat(events.first().amount).isEqualTo(50L)
        assertThat(events.first().source).isEqualTo(XpSource.Kill)
    }

    @Test
    fun `execute does not publish LeveledUp when no level change`() {
        useCase.execute(playerId, 50L, XpSource.Kill)
        assertThat(eventBus.publishedOfType<LeveledUp>()).isEmpty()
    }

    @Test
    fun `execute publishes LeveledUp when level increases`() {
        useCase.execute(playerId, 100L, XpSource.Kill)
        val levelUpEvents = eventBus.publishedOfType<LeveledUp>()
        assertThat(levelUpEvents).hasSize(1)
        assertThat(levelUpEvents.first().playerId).isEqualTo(playerId)
        assertThat(levelUpEvents.first().oldLevel).isEqualTo(Level.ONE)
        assertThat(levelUpEvents.first().newLevel).isEqualTo(Level(2))
    }

    @Test
    fun `execute XpGranted newLevel is set on level up`() {
        useCase.execute(playerId, 100L, XpSource.Kill)
        val xpEvent = eventBus.publishedOfType<XpGranted>().first()
        assertThat(xpEvent.newLevel).isEqualTo(2)
    }

    @Test
    fun `execute XpGranted newLevel is null when no level up`() {
        useCase.execute(playerId, 50L, XpSource.Kill)
        val xpEvent = eventBus.publishedOfType<XpGranted>().first()
        assertThat(xpEvent.newLevel).isNull()
    }

    @Test
    fun `execute returns success LevelChange`() {
        val result = useCase.execute(playerId, 100L, XpSource.MatchWin)
        assertThat(result.isSuccess).isTrue()
        val change = result.getOrNull()!!
        assertThat(change.leveledUp).isTrue()
        assertThat(change.newLevel).isEqualTo(Level(2))
    }
}
