package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.service.InMemoryStageRepository
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import com.astarworks.astera.core.domain.event.MatchStarted
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StartMatchUseCaseTest {

    private lateinit var stages: InMemoryStageRepository
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: StartMatchUseCase

    private val stageId = StageId("test-arena")

    @BeforeEach
    fun setUp() {
        stages = InMemoryStageRepository()
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 100L)
        useCase = StartMatchUseCase(stages, matches, eventBus, scheduler)
    }

    @Test
    fun `happy path - creates match in COUNTDOWN and publishes MatchStarted`() {
        stages.save(buildStage(stageId))

        val result = useCase.execute(stageId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val matchId = (result as Result.Success).value

        val saved = matches.findById(matchId)
        assertThat(saved).isNotNull
        assertThat(saved!!.phase).isEqualTo(MatchPhase.COUNTDOWN)
        assertThat(saved.stageId).isEqualTo(stageId)

        val events = eventBus.publishedOfType<MatchStarted>()
        assertThat(events).hasSize(1)
        assertThat(events.first().matchId).isEqualTo(matchId)
        assertThat(events.first().stageId).isEqualTo(stageId)
    }

    @Test
    fun `StageNotFound when stage does not exist`() {
        val result = useCase.execute(StageId("nonexistent"))

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(StartMatchError.StageNotFound::class.java)
    }

    @Test
    fun `AlreadyActive when active match exists for stage`() {
        stages.save(buildStage(stageId))
        // Start first match
        useCase.execute(stageId)

        // Attempt second
        val result = useCase.execute(stageId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(StartMatchError.AlreadyActive::class.java)
    }

    private fun buildStage(id: StageId) = Stage(
        id = id,
        displayNameKey = MessageKey("astera.stage.test-arena"),
        regions = emptyList<Region>(),
    )
}
