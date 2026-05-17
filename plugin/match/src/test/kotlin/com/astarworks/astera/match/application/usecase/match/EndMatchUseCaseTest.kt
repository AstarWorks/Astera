package com.astarworks.astera.match.application.usecase.match

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EndMatchUseCaseTest {

    private lateinit var matches: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var scheduler: FakeScheduler
    private lateinit var useCase: EndMatchUseCase

    @BeforeEach
    fun setUp() {
        matches = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        scheduler = FakeScheduler(startTick = 200L)
        useCase = EndMatchUseCase(matches, eventBus, scheduler)
    }

    @Test
    fun `happy path - ends an ACTIVE match and publishes MatchEnded`() {
        val matchId = MatchId("match-abc")
        matches.save(buildMatch(matchId, MatchPhase.ACTIVE))

        val result = useCase.execute(matchId, EndReason.AdminEnded)

        assertThat(result).isInstanceOf(Result.Success::class.java)

        val saved = matches.findById(matchId)
        assertThat(saved!!.phase).isEqualTo(MatchPhase.ENDED)

        val events = eventBus.publishedOfType<MatchEnded>()
        assertThat(events).hasSize(1)
        assertThat(events.first().matchId).isEqualTo(matchId)
        assertThat(events.first().reason).isEqualTo(EndReason.AdminEnded)
    }

    @Test
    fun `MatchNotFound when match does not exist`() {
        val result = useCase.execute(MatchId("match-xyz"), EndReason.AdminEnded)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(EndMatchError.MatchNotFound::class.java)
    }

    @Test
    fun `MatchAlreadyEnded when match is already ENDED`() {
        val matchId = MatchId("match-done")
        matches.save(buildMatch(matchId, MatchPhase.ENDED))

        val result = useCase.execute(matchId, EndReason.AdminEnded)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error)
            .isInstanceOf(EndMatchError.MatchAlreadyEnded::class.java)
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
