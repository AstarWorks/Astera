package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.match.domain.match.MatchStateMachine
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SwitchPhaseUseCaseTest {

    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: SwitchPhaseUseCase

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 50L)
        useCase = SwitchPhaseUseCase(matches, eventBus, scheduler)
    }

    @Test
    fun `happy path - COUNTDOWN to ACTIVE via CountdownComplete`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.COUNTDOWN))

        val result = useCase.execute(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isEqualTo(MatchPhase.ACTIVE)

        assertThat(matches.findById(matchId)!!.phase).isEqualTo(MatchPhase.ACTIVE)

        val events = eventBus.publishedOfType<PhaseChanged>()
        assertThat(events).hasSize(1)
        assertThat(events.first().from).isEqualTo(MatchPhase.COUNTDOWN)
        assertThat(events.first().to).isEqualTo(MatchPhase.ACTIVE)
    }

    @Test
    fun `MatchNotFound when match does not exist`() {
        val result = useCase.execute(
            MatchId("match-xyz"),
            MatchStateMachine.TransitionTrigger.CountdownComplete,
        )

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(SwitchPhaseError.MatchNotFound::class.java)
    }

    @Test
    fun `TransitionFailed when trigger is illegal for current phase`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.WAITING))

        // CountdownComplete is only legal from COUNTDOWN, not WAITING
        val result = useCase.execute(matchId, MatchStateMachine.TransitionTrigger.CountdownComplete)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(SwitchPhaseError.TransitionFailed::class.java)
    }

    private fun buildMatch(id: MatchId, phase: MatchPhase): Match {
        val teamId = TeamId("red")
        return Match(
            id = id,
            phase = phase,
            teams = listOf(
                Team(
                    id = teamId,
                    displayNameKey = MessageKey("astera.match.team.red"),
                    colorKey = MessageKey("astera.match.team.red.color"),
                    members = emptySet(),
                ),
            ),
            score = Score(mapOf(teamId to 0)),
            stageId = StageId("test-arena"),
            startedAtTick = 0L,
        )
    }
}
