package com.astarworks.astera.stats.application.service.leaderboard

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.domain.season.SeasonId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryLeaderboardRepositoryTest {

    private lateinit var repo: InMemoryLeaderboardRepository
    private val season = SeasonId("season-1")
    private val other = SeasonId("season-2")

    private fun player() = PlayerId(UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        repo = InMemoryLeaderboardRepository()
    }

    @Test
    fun `apply creates an entry then increments wins and losses`() {
        val p = player()
        repo.apply(season, p, rating = 1010, won = true)
        repo.apply(season, p, rating = 1025, won = true)
        repo.apply(season, p, rating = 1015, won = false)

        val entry = repo.entry(season, p)!!
        assertThat(entry.rating).isEqualTo(1015)
        assertThat(entry.wins).isEqualTo(2L)
        assertThat(entry.losses).isEqualTo(1L)
        assertThat(entry.gamesPlayed).isEqualTo(3L)
    }

    @Test
    fun `topN orders by rating descending`() {
        val low = player()
        val high = player()
        val mid = player()
        repo.apply(season, low, rating = 900, won = true)
        repo.apply(season, high, rating = 1200, won = true)
        repo.apply(season, mid, rating = 1000, won = true)

        val top = repo.topN(season, 10)
        assertThat(top.map { it.playerId }).containsExactly(high, mid, low)
    }

    @Test
    fun `topN tie-breaks equal rating by winRate then gamesPlayed`() {
        val a = player() // rating 1000, 3W/1L -> winRate 0.75
        val b = player() // rating 1000, 1W/1L -> winRate 0.50
        val c = player() // rating 1000, 6W/2L -> winRate 0.75, more games than a
        repeat(3) { repo.apply(season, a, rating = 1000, won = true) }
        repo.apply(season, a, rating = 1000, won = false)

        repo.apply(season, b, rating = 1000, won = true)
        repo.apply(season, b, rating = 1000, won = false)

        repeat(6) { repo.apply(season, c, rating = 1000, won = true) }
        repeat(2) { repo.apply(season, c, rating = 1000, won = false) }

        // c and a share winRate 0.75; c has more games so ranks first. b ranks last.
        assertThat(repo.topN(season, 10).map { it.playerId }).containsExactly(c, a, b)
    }

    @Test
    fun `topN respects the limit and ignores non-positive limits`() {
        repeat(5) { repo.apply(season, player(), rating = 1000 + it, won = true) }
        assertThat(repo.topN(season, 3)).hasSize(3)
        assertThat(repo.topN(season, 0)).isEmpty()
        assertThat(repo.topN(season, -1)).isEmpty()
    }

    @Test
    fun `rankOf is 1-based and null for unknown player or season`() {
        val first = player()
        val second = player()
        repo.apply(season, first, rating = 1200, won = true)
        repo.apply(season, second, rating = 1000, won = true)

        assertThat(repo.rankOf(season, first)).isEqualTo(1)
        assertThat(repo.rankOf(season, second)).isEqualTo(2)
        assertThat(repo.rankOf(season, player())).isNull()
        assertThat(repo.rankOf(other, first)).isNull()
    }

    @Test
    fun `entries are isolated per season`() {
        val p = player()
        repo.apply(season, p, rating = 1100, won = true)
        repo.apply(other, p, rating = 800, won = false)

        assertThat(repo.entry(season, p)!!.rating).isEqualTo(1100)
        assertThat(repo.entry(season, p)!!.wins).isEqualTo(1L)
        assertThat(repo.entry(other, p)!!.rating).isEqualTo(800)
        assertThat(repo.entry(other, p)!!.losses).isEqualTo(1L)
    }

    @Test
    fun `entry is null for an untouched season`() {
        assertThat(repo.entry(season, player())).isNull()
    }
}
