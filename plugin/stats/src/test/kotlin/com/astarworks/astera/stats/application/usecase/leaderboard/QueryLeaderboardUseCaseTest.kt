package com.astarworks.astera.stats.application.usecase.leaderboard

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.application.service.leaderboard.InMemoryLeaderboardRepository
import com.astarworks.astera.stats.application.service.season.InMemorySeasonRepository
import com.astarworks.astera.stats.domain.season.Season
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QueryLeaderboardUseCaseTest {

    private lateinit var leaderboard: InMemoryLeaderboardRepository
    private lateinit var seasons: InMemorySeasonRepository
    private lateinit var useCase: QueryLeaderboardUseCase

    @BeforeEach
    fun setUp() {
        leaderboard = InMemoryLeaderboardRepository()
        seasons = InMemorySeasonRepository(clock = { Instant.parse("2026-01-01T00:00:00Z") })
        useCase = QueryLeaderboardUseCase(leaderboard, seasons)
    }

    @Test
    fun `execute returns the top entries of the current season`() {
        val seasonId = seasons.current()!!.id
        val top = PlayerId(UUID.randomUUID())
        val bottom = PlayerId(UUID.randomUUID())
        leaderboard.apply(seasonId, top, rating = 1300, won = true)
        leaderboard.apply(seasonId, bottom, rating = 900, won = false)

        val result = useCase.execute(limit = 10)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(result.getOrNull()!!.map { it.playerId }).containsExactly(top, bottom)
    }

    @Test
    fun `execute honours the limit`() {
        val seasonId = seasons.current()!!.id
        repeat(5) { leaderboard.apply(seasonId, PlayerId(UUID.randomUUID()), rating = 1000 + it, won = true) }
        assertThat(useCase.execute(limit = 2).getOrNull()).hasSize(2)
    }

    @Test
    fun `execute fails with InvalidLimit for a non-positive limit`() {
        assertThat(useCase.execute(limit = 0).errorOrNull())
            .isEqualTo(QueryLeaderboardUseCase.LeaderboardError.InvalidLimit(0))
    }

    @Test
    fun `execute fails with NoActiveSeason when no season is open`() {
        val empty = QueryLeaderboardUseCase(leaderboard, NoSeasonRepository)
        assertThat(empty.execute(limit = 10).errorOrNull())
            .isEqualTo(QueryLeaderboardUseCase.LeaderboardError.NoActiveSeason)
    }

    private object NoSeasonRepository : ISeasonRepository {
        override fun current(): Season? = null
        override fun start(label: String, at: Instant): Season = error("not used")
        override fun all(): List<Season> = emptyList()
    }
}
