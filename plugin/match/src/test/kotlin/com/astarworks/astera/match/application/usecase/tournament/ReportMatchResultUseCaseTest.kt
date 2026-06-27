package com.astarworks.astera.match.application.usecase.tournament

import com.astarworks.astera.core.domain.event.TournamentConcluded
import com.astarworks.astera.core.domain.event.TournamentRoundAdvanced
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.tournament.InMemoryTournamentRepository
import com.astarworks.astera.match.domain.tournament.BracketAdvancer
import com.astarworks.astera.match.domain.tournament.Tournament
import com.astarworks.astera.match.domain.tournament.TournamentError
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ReportMatchResultUseCaseTest {

    private fun players(n: Int): List<PlayerId> =
        List(n) { PlayerId(UUID(0L, it.toLong())) }

    private val repository = InMemoryTournamentRepository()
    private val eventBus = FakeDomainEventBus()
    private val useCase = ReportMatchResultUseCase(repository, eventBus)

    private fun seed(id: String, seeds: List<PlayerId>): TournamentId {
        val tournamentId = TournamentId(id)
        repository.upsert(
            Tournament(
                id = tournamentId,
                name = "Cup",
                seeds = seeds,
                rounds = BracketAdvancer.seed(seeds),
                status = TournamentStatus.PENDING,
            ),
        )
        return tournamentId
    }

    @Test
    fun `unknown tournament fails with NotFound`() {
        val result = useCase.execute(TournamentId("ghost"), players(2)[0])

        assertThat(result.errorOrNull()).isEqualTo(TournamentError.NotFound(TournamentId("ghost")))
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `final report concludes the tournament and publishes TournamentConcluded`() {
        val p = players(2)
        val id = seed("final", p)

        val result = useCase.execute(id, p[0])

        val tournament = result.getOrNull()!!
        assertThat(tournament.status).isEqualTo(TournamentStatus.CONCLUDED)
        assertThat(tournament.winner).isEqualTo(p[0])

        val concluded = eventBus.publishedOfType<TournamentConcluded>()
        assertThat(concluded).hasSize(1)
        assertThat(concluded[0].tournamentId).isEqualTo("final")
        assertThat(concluded[0].winnerPlayerId).isEqualTo(p[0])
        // A single-round tournament emits no round-advance event.
        assertThat(eventBus.publishedOfType<TournamentRoundAdvanced>()).isEmpty()
    }

    @Test
    fun `completing a non-final round publishes TournamentRoundAdvanced`() {
        val p = players(4)
        val id = seed("semis", p)

        // Round 0 has two matches [p0 vs p3] and [p1 vs p2]; both must report.
        useCase.execute(id, p[0])
        assertThat(eventBus.publishedOfType<TournamentRoundAdvanced>()).isEmpty()

        val result = useCase.execute(id, p[1])
        val tournament = result.getOrNull()!!
        assertThat(tournament.status).isEqualTo(TournamentStatus.ACTIVE)

        val advanced = eventBus.publishedOfType<TournamentRoundAdvanced>()
        assertThat(advanced).hasSize(1)
        assertThat(advanced[0].tournamentId).isEqualTo("semis")
        assertThat(advanced[0].roundIndex).isEqualTo(0)
        assertThat(eventBus.publishedOfType<TournamentConcluded>()).isEmpty()
    }

    @Test
    fun `reporting against a concluded tournament fails`() {
        val p = players(2)
        val id = seed("done", p)
        useCase.execute(id, p[0])

        val result = useCase.execute(id, p[1])
        assertThat(result.errorOrNull()).isEqualTo(TournamentError.AlreadyConcluded(id))
    }

    @Test
    fun `re-reporting an already-decided match fails with AlreadyDecided`() {
        val p = players(4)
        val id = seed("again", p)
        useCase.execute(id, p[0])

        val result = useCase.execute(id, p[0])
        assertThat(result.errorOrNull()).isEqualTo(TournamentError.AlreadyDecided(id, p[0]))
    }

    @Test
    fun `reporting a non-participant fails with PlayerNotInCurrentRound`() {
        val p = players(4)
        val id = seed("stranger", p)
        val outsider = PlayerId(UUID(0L, 99L))

        val result = useCase.execute(id, outsider)
        assertThat(result.errorOrNull()).isEqualTo(TournamentError.PlayerNotInCurrentRound(id, outsider))
    }
}
