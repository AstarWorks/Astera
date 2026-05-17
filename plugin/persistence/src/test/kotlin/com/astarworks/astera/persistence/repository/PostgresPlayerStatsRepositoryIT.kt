package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Smoke / round-trip IT for [PostgresPlayerStatsRepository] (Worker α territory)
 * against a real Postgres via Testcontainers. Co-owned with the ε bootstrap
 * because the harness wiring is verified end-to-end through this test.
 */
public class PostgresPlayerStatsRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `upsert + find round-trips a PlayerStats`() {
        val repo = PostgresPlayerStatsRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val stats = PlayerStats(
            playerId = id,
            kills = 7L,
            deaths = 3L,
            assists = 2L,
            damageDealt = 1500L,
            damageTaken = 900L,
            playtimeSec = 1200L,
            matchesPlayed = 4L,
            wins = 2L,
            losses = 2L,
        )
        repo.upsert(stats)
        val found = repo.find(id)
        assertThat(found).isNotNull
        assertThat(found!!.kills).isEqualTo(7L)
        assertThat(found.wins).isEqualTo(2L)
        assertThat(found.deaths).isEqualTo(3L)
    }

    @Test
    public fun `all() returns inserted rows`() {
        val repo = PostgresPlayerStatsRepository(db)
        val before = repo.all().size
        repo.upsert(
            PlayerStats(
                playerId = PlayerId(UUID.randomUUID()),
                kills = 1L,
                deaths = 0L,
                assists = 0L,
                damageDealt = 0L,
                damageTaken = 0L,
                playtimeSec = 0L,
                matchesPlayed = 0L,
            ),
        )
        repo.upsert(
            PlayerStats(
                playerId = PlayerId(UUID.randomUUID()),
                kills = 2L,
                deaths = 0L,
                assists = 0L,
                damageDealt = 0L,
                damageTaken = 0L,
                playtimeSec = 0L,
                matchesPlayed = 0L,
            ),
        )
        assertThat(repo.all().size).isGreaterThanOrEqualTo(before + 2)
    }
}
