package com.astarworks.astera.match.application.usecase.rating

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.domain.event.EndReason
import com.astarworks.astera.core.domain.event.MatchEnded
import com.astarworks.astera.core.domain.event.PlayerRatingUpdated
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.match.Match
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.match.MatchPhase
import com.astarworks.astera.core.domain.match.Score
import com.astarworks.astera.core.domain.match.Team
import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stage.StageId
import com.astarworks.astera.match.application.service.rating.InMemoryPlayerRatingRepository
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class UpdateRatingOnMatchEndedUseCaseTest {

    /** Local fake — match-typed fakes may not live in the core-only test-fixtures module. */
    private class FakeMatchRepository(private val matches: MutableMap<MatchId, Match> = mutableMapOf()) :
        IMatchRepository {
        override fun findById(id: MatchId): Match? = matches[id]
        override fun all(): Collection<Match> = matches.values
        override fun save(entity: Match) { matches[entity.id] = entity }
        override fun delete(id: MatchId) { matches.remove(id) }
        override fun findActiveMatches(): Collection<Match> = matches.values
        override fun findMatchOf(playerId: PlayerId): Match? =
            matches.values.firstOrNull { it.hasParticipant(playerId) }
        override fun findInPhase(phase: MatchPhase): Collection<Match> =
            matches.values.filter { it.phase == phase }
    }

    private fun pid() = PlayerId(UUID.randomUUID())

    private fun team(id: String, members: List<PlayerId>) = Team(
        id = TeamId(id),
        displayNameKey = MessageKey("astera.team.$id.name"),
        colorKey = MessageKey("astera.team.$id.color"),
        members = members.toSet(),
    )

    private fun match(id: String, teams: List<Team>) = Match(
        id = MatchId(id),
        phase = MatchPhase.ENDED,
        teams = teams,
        score = Score.EMPTY,
        stageId = StageId("arena"),
        startedAtTick = 0,
    )

    private fun ended(id: String, winningTeam: TeamId?) = MatchEnded(
        matchId = MatchId(id),
        winningTeam = winningTeam,
        atTick = 100,
        reason = EndReason.ObjectiveCompleted,
    )

    @Test
    fun `winner gains rating and loser loses rating`() {
        val winner = pid(); val loser = pid()
        val repo = FakeMatchRepository()
        repo.save(match("m1", listOf(team("red", listOf(winner)), team("blue", listOf(loser)))))
        val ratings = InMemoryPlayerRatingRepository()
        val bus = FakeDomainEventBus()
        val useCase = UpdateRatingOnMatchEndedUseCase(repo, ratings, bus)

        useCase.onMatchEnded(ended("m1", TeamId("red")))

        assertThat(ratings.ratingOf(winner).value).isGreaterThan(1000)
        assertThat(ratings.ratingOf(loser).value).isLessThan(1000)
    }

    @Test
    fun `publishes one PlayerRatingUpdated per participant with the won flag`() {
        val w1 = pid(); val w2 = pid(); val l1 = pid(); val l2 = pid()
        val repo = FakeMatchRepository()
        repo.save(match("m2", listOf(team("red", listOf(w1, w2)), team("blue", listOf(l1, l2)))))
        val bus = FakeDomainEventBus()
        val useCase = UpdateRatingOnMatchEndedUseCase(repo, InMemoryPlayerRatingRepository(), bus)

        useCase.onMatchEnded(ended("m2", TeamId("red")))

        val events = bus.published.filterIsInstance<PlayerRatingUpdated>()
        assertThat(events).hasSize(4)
        assertThat(events.filter { it.playerId in setOf(w1, w2) }).allMatch { it.won }
        assertThat(events.filter { it.playerId in setOf(l1, l2) }).allMatch { !it.won }
        assertThat(events).allMatch { it.matchId == MatchId("m2") }
        val winnerEvent = events.first { it.playerId == w1 }
        assertThat(winnerEvent.newRating).isGreaterThan(winnerEvent.oldRating)
    }

    @Test
    fun `a draw leaves ratings unchanged and publishes nothing`() {
        val a = pid(); val b = pid()
        val repo = FakeMatchRepository()
        repo.save(match("m3", listOf(team("red", listOf(a)), team("blue", listOf(b)))))
        val ratings = InMemoryPlayerRatingRepository()
        val bus = FakeDomainEventBus()
        val useCase = UpdateRatingOnMatchEndedUseCase(repo, ratings, bus)

        useCase.onMatchEnded(ended("m3", winningTeam = null))

        assertThat(ratings.all()).isEmpty()
        assertThat(bus.published.filterIsInstance<PlayerRatingUpdated>()).isEmpty()
    }

    @Test
    fun `unknown match is a no-op`() {
        val bus = FakeDomainEventBus()
        val ratings = InMemoryPlayerRatingRepository()
        val useCase = UpdateRatingOnMatchEndedUseCase(FakeMatchRepository(), ratings, bus)

        useCase.onMatchEnded(ended("missing", TeamId("red")))

        assertThat(bus.published.filterIsInstance<PlayerRatingUpdated>()).isEmpty()
        assertThat(ratings.all()).isEmpty()
    }
}
