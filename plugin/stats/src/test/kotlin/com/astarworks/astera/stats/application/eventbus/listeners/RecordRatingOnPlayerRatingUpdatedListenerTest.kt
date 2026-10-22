package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.core.domain.event.PlayerRatingUpdated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.ISeasonRepository
import com.astarworks.astera.stats.application.service.leaderboard.InMemoryLeaderboardRepository
import com.astarworks.astera.stats.domain.season.Season
import com.astarworks.astera.stats.domain.season.SeasonId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class RecordRatingOnPlayerRatingUpdatedListenerTest {

    private val player = PlayerId(UUID.randomUUID())
    private val season = Season(SeasonId("season-1"), "Season 1", Instant.parse("2026-01-01T00:00:00Z"))

    private fun event(won: Boolean) = PlayerRatingUpdated(
        playerId = player,
        oldRating = 1000,
        newRating = 1025,
        matchId = MatchId("m-1"),
        won = won,
    )

    /** Minimal [ISeasonRepository] stub returning a fixed current season. */
    private class FixedSeasonRepository(private val season: Season?) : ISeasonRepository {
        override fun current(): Season? = season
        override fun start(label: String, at: Instant): Season = error("not used")
        override fun startOnce(scheduleKey: String, label: String, at: java.time.Instant): Season? = error("Not used")
        override fun all(): List<Season> = listOfNotNull(season)
    }

    @Test
    fun `handle materialises a leaderboard entry in the current season`() {
        val leaderboard = InMemoryLeaderboardRepository()
        val listener = RecordRatingOnPlayerRatingUpdatedListener(FixedSeasonRepository(season), leaderboard)

        listener.handle(event(won = true))

        val entry = leaderboard.entry(season.id, player)!!
        assertThat(entry.rating).isEqualTo(1025)
        assertThat(entry.wins).isEqualTo(1L)
        assertThat(entry.losses).isEqualTo(0L)
    }

    @Test
    fun `handle records a loss when the player did not win`() {
        val leaderboard = InMemoryLeaderboardRepository()
        val listener = RecordRatingOnPlayerRatingUpdatedListener(FixedSeasonRepository(season), leaderboard)

        listener.handle(event(won = false))

        val entry = leaderboard.entry(season.id, player)!!
        assertThat(entry.wins).isEqualTo(0L)
        assertThat(entry.losses).isEqualTo(1L)
    }

    @Test
    fun `handle is a no-op when no season is active`() {
        val leaderboard = InMemoryLeaderboardRepository()
        val listener = RecordRatingOnPlayerRatingUpdatedListener(FixedSeasonRepository(null), leaderboard)

        listener.handle(event(won = true))

        assertThat(leaderboard.entry(season.id, player)).isNull()
    }
}
