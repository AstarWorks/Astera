package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.MatchStarted
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.Region
import com.astarworks.astera.core.domain.stage.Stage
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.contents.StageContents
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wave 5wireup (ADR-0116 §G16) — StartMatchUseCase Annihilation 3-team
 * formation test.
 */
class StartMatchUseCaseAnnihilationTest {

    private lateinit var stages: StageContents
    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: StartMatchUseCase

    private val stageId = StageId("test-annihilation-arena")

    @BeforeEach
    fun setUp() {
        stages = StageContents()
        stages.register(
            stageId,
            Stage(
                id = stageId,
                displayNameKey = MessageKey("astera.stage.test-annihilation-arena"),
                regions = emptyList<Region>(),
            ),
        )
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler()
        useCase = StartMatchUseCase(stages, matches, eventBus, scheduler)
    }

    @Test
    fun `default mode keeps 2-team formation (red, blue)`() {
        val result = useCase.execute(stageId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val matchId = (result as Result.Success).value
        val match = matches.findById(matchId)!!
        assertThat(match.teams.map { it.id }).containsExactly(TeamId("red"), TeamId("blue"))

        val event = eventBus.publishedOfType<MatchStarted>().single()
        assertThat(event.modeKey).isNull()
    }

    @Test
    fun `annihilation mode yields 3-team formation (red, blue, yellow)`() {
        val result = useCase.execute(
            stageId,
            modeKey = StartMatchUseCase.MODE_KEY_ANNIHILATION,
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val matchId = (result as Result.Success).value
        val match = matches.findById(matchId)!!
        assertThat(match.teams.map { it.id })
            .containsExactly(TeamId("red"), TeamId("blue"), TeamId("yellow"))

        val event = eventBus.publishedOfType<MatchStarted>().single()
        assertThat(event.modeKey).isEqualTo("annihilation")
    }

    @Test
    fun `explicit teamIds override the mode default`() {
        val result = useCase.execute(
            stageId,
            modeKey = StartMatchUseCase.MODE_KEY_ANNIHILATION,
            teamIds = listOf(TeamId("alpha"), TeamId("beta")),
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val matchId = (result as Result.Success).value
        val match = matches.findById(matchId)!!
        assertThat(match.teams.map { it.id }).containsExactly(TeamId("alpha"), TeamId("beta"))
    }
}
