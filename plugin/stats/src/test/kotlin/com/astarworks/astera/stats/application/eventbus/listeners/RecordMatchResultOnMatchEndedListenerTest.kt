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
import com.astarworks.astera.stats.application.usecase.stats.RecordMatchResultUseCase
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class RecordMatchResultOnMatchEndedListenerTest {

    private lateinit var statsRepo: InMemoryPlayerStatsRepository
    private lateinit var matchRepo: InMemoryMatchRepository
    private lateinit var eventBus: FakeDomainEventBus
    private lateinit var listener: RecordMatchResultOnMatchEndedListener

    private val winner1 = PlayerId(UUID.randomUUID())
    private val winner2 = PlayerId(UUID.randomUUID())
    private val loser1 = PlayerId(UUID.randomUUID())
    private val loser2 = PlayerId(UUID.randomUUID())
    private val teamA = TeamId("red")
    private val teamB = TeamId("blue")
    private val matchId = MatchId("m-2")
    private val stageId = StageId("arena")

    @BeforeEach
    fun setUp() {
        statsRepo = InMemoryPlayerStatsRepository()
        matchRepo = InMemoryMatchRepository()
        eventBus = FakeDomainEventBus()
        val uc = RecordMatchResultUseCase(statsRepo, eventBus)
        listener = RecordMatchResultOnMatchEndedListener(matchRepo, uc)
    }

    private fun team(id: TeamId, members: Set<PlayerId>) = Team(
        id = id,
        displayNameKey = MessageKey("astera.team.${id.value}.name"),
        colorKey = MessageKey("astera.team.${id.value}.color"),
        members = members,
    )

    private fun makeMatch(teams: List<Team>) = Match(
        id = matchId,
        phase = MatchPhase.ENDED,
        teams = teams,
        score = Score.EMPTY,
        stageId = stageId,
        startedAtTick = 0L,
    )

    private fun eventWithWinner(winner: TeamId?) = MatchEnded(
        matchId = matchId,
        winningTeam = winner,
        atTick = 100L,
        reason = if (winner != null) EndReason.ScoreThresholdReached else EndReason.TimerExpired,
    )

    @Test
    fun `winning team members get wins++`() {
        matchRepo.save(
            makeMatch(listOf(team(teamA, setOf(winner1, winner2)), team(teamB, setOf(loser1)))),
        )
        listener.handle(eventWithWinner(teamA))

        assertThat(statsRepo.find(winner1)!!.wins).isEqualTo(1L)
        assertThat(statsRepo.find(winner1)!!.losses).isEqualTo(0L)
        assertThat(statsRepo.find(winner2)!!.wins).isEqualTo(1L)
    }

    @Test
    fun `losing team members get losses++`() {
        matchRepo.save(
            makeMatch(listOf(team(teamA, setOf(winner1)), team(teamB, setOf(loser1, loser2)))),
        )
        listener.handle(eventWithWinner(teamA))

        assertThat(statsRepo.find(loser1)!!.losses).isEqualTo(1L)
        assertThat(statsRepo.find(loser1)!!.wins).isEqualTo(0L)
        assertThat(statsRepo.find(loser2)!!.losses).isEqualTo(1L)
    }

    @Test
    fun `draw (null winningTeam) records no W or L`() {
        matchRepo.save(
            makeMatch(listOf(team(teamA, setOf(winner1)), team(teamB, setOf(loser1)))),
        )
        listener.handle(eventWithWinner(null))

        assertThat(statsRepo.find(winner1)).isNull()
        assertThat(statsRepo.find(loser1)).isNull()
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `publishes StatsUpdated Win for winners and Loss for losers`() {
        matchRepo.save(
            makeMatch(listOf(team(teamA, setOf(winner1)), team(teamB, setOf(loser1)))),
        )
        listener.handle(eventWithWinner(teamA))

        val updates = eventBus.publishedOfType<StatsUpdated>()
        assertThat(updates).anyMatch { it.playerId == winner1 && it.kind == StatsUpdated.Kind.Win }
        assertThat(updates).anyMatch { it.playerId == loser1 && it.kind == StatsUpdated.Kind.Loss }
        assertThat(updates).hasSize(2)
    }

    @Test
    fun `does nothing when match cannot be found`() {
        // No match saved
        listener.handle(eventWithWinner(teamA))

        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `three-team match credits winner team and losses to the other two`() {
        val teamC = TeamId("green")
        val p3 = PlayerId(UUID.randomUUID())
        matchRepo.save(
            makeMatch(listOf(
                team(teamA, setOf(winner1)),
                team(teamB, setOf(loser1)),
                team(teamC, setOf(p3)),
            )),
        )
        listener.handle(eventWithWinner(teamA))

        assertThat(statsRepo.find(winner1)!!.wins).isEqualTo(1L)
        assertThat(statsRepo.find(loser1)!!.losses).isEqualTo(1L)
        assertThat(statsRepo.find(p3)!!.losses).isEqualTo(1L)
        assertThat(statsRepo.find(p3)!!.wins).isEqualTo(0L)
    }
}
