package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.InMemoryMatchRepository
import com.astarworks.astera.match.domain.event.MatchEnded
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.stats.application.service.InMemoryPlayerStatsRepository
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchPlayedUseCase
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordMatchPlayedOnMatchEndedListenerTest {

    private lateinit var statsRepo: InMemoryPlayerStatsRepository
    private lateinit var matchRepo: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: RecordMatchPlayedOnMatchEndedListener

    private val p1 = PlayerId(UUID.randomUUID())
    private val p2 = PlayerId(UUID.randomUUID())
    private val p3 = PlayerId(UUID.randomUUID())
    private val teamA = TeamId("red")
    private val teamB = TeamId("blue")
    private val matchId = MatchId("m-1")
    private val stageId = StageId("arena")

    @BeforeEach
    fun setUp() {
        statsRepo = InMemoryPlayerStatsRepository()
        matchRepo = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        val uc = RecordMatchPlayedUseCase(statsRepo, eventBus)
        listener = RecordMatchPlayedOnMatchEndedListener(matchRepo, uc)
    }

    private fun makeMatch(teams: List<Team>) = Match(
        id = matchId,
        phase = MatchPhase.ENDED,
        teams = teams,
        score = Score.EMPTY,
        stageId = stageId,
        startedAtTick = 0L,
    )

    private fun team(id: TeamId, members: Set<PlayerId>) = Team(
        id = id,
        displayNameKey = MessageKey("astera.team.${id.value}.name"),
        colorKey = MessageKey("astera.team.${id.value}.color"),
        members = members,
    )

    private val event = MatchEnded(
        matchId = matchId,
        winningTeam = teamA,
        atTick = 100L,
        reason = EndReason.ScoreThresholdReached,
    )

    @Test
    fun `increments matchesPlayed for every participant on every team`() {
        matchRepo.save(makeMatch(listOf(team(teamA, setOf(p1, p2)), team(teamB, setOf(p3)))))
        listener.handle(event)

        assertThat(statsRepo.find(p1)!!.matchesPlayed).isEqualTo(1L)
        assertThat(statsRepo.find(p2)!!.matchesPlayed).isEqualTo(1L)
        assertThat(statsRepo.find(p3)!!.matchesPlayed).isEqualTo(1L)
    }

    @Test
    fun `publishes StatsUpdated MatchPlayed for every participant`() {
        matchRepo.save(makeMatch(listOf(team(teamA, setOf(p1, p2)), team(teamB, setOf(p3)))))
        listener.handle(event)

        val statsEvents = eventBus.publishedOfType<StatsUpdated>()
            .filter { it.kind == StatsUpdated.Kind.MatchPlayed }
        assertThat(statsEvents).hasSize(3)
        assertThat(statsEvents.map { it.playerId }).containsExactlyInAnyOrder(p1, p2, p3)
    }

    @Test
    fun `does nothing when match cannot be found`() {
        // Match never registered in matchRepo
        listener.handle(event)

        assertThat(statsRepo.find(p1)).isNull()
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `still records every player when a team has no members`() {
        matchRepo.save(makeMatch(listOf(team(teamA, setOf(p1)), team(teamB, emptySet()))))
        listener.handle(event)

        assertThat(statsRepo.find(p1)!!.matchesPlayed).isEqualTo(1L)
        assertThat(eventBus.publishedOfType<StatsUpdated>()).hasSize(1)
    }

    @Test
    fun `accumulates matchesPlayed across multiple MatchEnded events`() {
        matchRepo.save(makeMatch(listOf(team(teamA, setOf(p1)))))
        listener.handle(event)
        listener.handle(event)
        listener.handle(event)

        assertThat(statsRepo.find(p1)!!.matchesPlayed).isEqualTo(3L)
    }

    @Test
    fun `records players even for a draw (winningTeam null)`() {
        matchRepo.save(makeMatch(listOf(team(teamA, setOf(p1)), team(teamB, setOf(p2)))))
        val draw = event.copy(winningTeam = null, reason = EndReason.TimerExpired)
        listener.handle(draw)

        assertThat(statsRepo.find(p1)!!.matchesPlayed).isEqualTo(1L)
        assertThat(statsRepo.find(p2)!!.matchesPlayed).isEqualTo(1L)
    }
}
