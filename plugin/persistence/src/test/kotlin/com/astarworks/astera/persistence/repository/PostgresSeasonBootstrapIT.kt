package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.event.PlayerRatingUpdated
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import com.astarworks.astera.stats.application.eventbus.listeners.RecordRatingOnPlayerRatingUpdatedListener
import com.astarworks.astera.stats.application.usecase.leaderboard.QueryLeaderboardUseCase
import com.astarworks.astera.stats.domain.season.SeasonId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class PostgresSeasonBootstrapIT : PostgresIntegrationTestBase() {
    @Test
    fun `fresh migrated database accepts ranked results without a manual season reset`() {
        kotlinx.coroutines.runBlocking {
        val seasons = PostgresSeasonRepository(db)
        val rankings = PostgresLeaderboardRepository(db)
        val player = PlayerId(UUID.randomUUID())
        assertThat(seasons.current()?.id).isEqualTo(SeasonId("season-1"))
        RecordRatingOnPlayerRatingUpdatedListener(seasons, rankings).handle(
            PlayerRatingUpdated(playerId = player, oldRating = 1000, newRating = 1020, won = true, matchId = MatchId("ranked-first")),
        )
        val entries = QueryLeaderboardUseCase(rankings, seasons).execute(10).getOrNull()!!
        assertThat(entries).hasSize(1)
        val entry = entries.single()
        assertThat(entry.playerId).isEqualTo(player)
        assertThat(entry.rating).isEqualTo(1020)
        assertThat(entry.wins).isEqualTo(1L)
        }
}
}
