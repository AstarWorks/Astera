package com.astarworks.astera.match.application.eventbus.listeners.victory

import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.application.usecase.match.SwitchPhaseUseCase
import com.astarworks.astera.match.domain.event.EntityPickedUpItem
import com.astarworks.astera.match.domain.event.PhaseChanged
import com.astarworks.astera.match.domain.match.PhaseTimings
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class NormaVictoryConditionEvaluatorListenerTest {

    private val matchId = MatchId("victory-test")
    private val p1 = PlayerId(UUID.randomUUID())

    private fun buildMatch(redScore: Int, blueScore: Int, phase: MatchPhase = MatchPhase.ACTIVE): Match = Match(
        id = matchId,
        phase = phase,
        teams = listOf(
            Team(TeamId("red"), MessageKey("a"), MessageKey("a"), setOf(p1)),
            Team(TeamId("blue"), MessageKey("a"), MessageKey("a"), emptySet()),
        ),
        score = Score(mapOf(TeamId("red") to redScore, TeamId("blue") to blueScore)),
        stageId = StageId("any"),
        startedAtTick = 0L,
    )

    @Test
    fun `triggers SwitchPhase ScoreThresholdReached when a unique winner emerges`() {
        val matches = InMemoryMatchRepository().apply {
            save(buildMatch(redScore = PhaseTimings.SCORE_THRESHOLD, blueScore = 0))
        }
        val bus = FakeDomainEventBus()
        val listener = NormaVictoryConditionEvaluatorListener(
            matches = matches,
            switchPhase = SwitchPhaseUseCase(matches = matches, eventBus = bus, scheduler = FakeScheduler()),
        )

        listener.handle(
            EntityPickedUpItem(
                matchId = matchId,
                playerId = p1,
                itemId = "astera:star",
                quantity = 1,
                atTick = 100L,
            ),
        )

        val phaseChanges = bus.published.filterIsInstance<PhaseChanged>()
        assertThat(phaseChanges).hasSize(1)
    }

    @Test
    fun `no trigger when no team reaches the threshold`() {
        val matches = InMemoryMatchRepository().apply {
            save(buildMatch(redScore = 1, blueScore = 1))
        }
        val bus = FakeDomainEventBus()
        val listener = NormaVictoryConditionEvaluatorListener(
            matches = matches,
            switchPhase = SwitchPhaseUseCase(matches, bus, FakeScheduler()),
        )

        listener.handle(
            EntityPickedUpItem(
                matchId = matchId,
                playerId = p1,
                itemId = "astera:star",
                quantity = 1,
                atTick = 100L,
            ),
        )

        assertThat(bus.published.filterIsInstance<PhaseChanged>()).isEmpty()
    }

    @Test
    fun `no trigger when scores are tied at threshold`() {
        val matches = InMemoryMatchRepository().apply {
            save(buildMatch(redScore = PhaseTimings.SCORE_THRESHOLD, blueScore = PhaseTimings.SCORE_THRESHOLD))
        }
        val bus = FakeDomainEventBus()
        val listener = NormaVictoryConditionEvaluatorListener(
            matches = matches,
            switchPhase = SwitchPhaseUseCase(matches, bus, FakeScheduler()),
        )

        listener.handle(
            EntityPickedUpItem(
                matchId = matchId,
                playerId = p1,
                itemId = "astera:star",
                quantity = 1,
                atTick = 100L,
            ),
        )

        assertThat(bus.published.filterIsInstance<PhaseChanged>()).isEmpty()
    }
}
