package com.astarworks.astera.match.application.usecase.tournament

import com.astarworks.astera.core.domain.event.TournamentStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.service.tournament.InMemoryTournamentRepository
import com.astarworks.astera.match.domain.tournament.TournamentError
import com.astarworks.astera.match.domain.tournament.TournamentId
import com.astarworks.astera.match.domain.tournament.TournamentStatus
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateTournamentUseCaseTest {

    private fun players(n: Int): List<PlayerId> =
        List(n) { PlayerId(UUID(0L, it.toLong())) }

    private val repository = InMemoryTournamentRepository()
    private val eventBus = FakeDomainEventBus()
    private val useCase = CreateTournamentUseCase(repository, eventBus)

    @Test
    fun `creates a seeded tournament and publishes TournamentStarted`() {
        val seeds = players(4)
        val result = useCase.execute(TournamentId("spring-cup"), "Spring Cup", seeds)

        assertThat(result.isSuccess).isTrue()
        val tournament = result.getOrNull()!!
        assertThat(tournament.status).isEqualTo(TournamentStatus.PENDING)
        assertThat(tournament.rounds).hasSize(2)
        assertThat(repository.find(TournamentId("spring-cup"))).isEqualTo(tournament)

        val started = eventBus.publishedOfType<TournamentStarted>()
        assertThat(started).hasSize(1)
        assertThat(started[0].tournamentId).isEqualTo("spring-cup")
        assertThat(started[0].participantCount).isEqualTo(4)
        assertThat(started[0].roundCount).isEqualTo(2)
    }

    @Test
    fun `rejects fewer than two seeds`() {
        val result = useCase.execute(TournamentId("solo"), "Solo", players(1))

        assertThat(result.errorOrNull()).isEqualTo(TournamentError.TooFewSeeds(1))
        assertThat(eventBus.published).isEmpty()
    }

    @Test
    fun `rejects duplicate seeds`() {
        val p = players(2)
        val result = useCase.execute(TournamentId("dupe"), "Dupe", listOf(p[0], p[1], p[0]))

        assertThat(result.errorOrNull()).isEqualTo(TournamentError.DuplicateSeed(p[0]))
        assertThat(repository.all()).isEmpty()
        assertThat(eventBus.published).isEmpty()
    }
}
