package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** ADR-0195: V49 `player_profiles` and V50 `player_xp` round trips against real Postgres. */
public class PostgresProfileAndXpRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `profile insertIfAbsent is first-writer-wins and survives a new repository instance`() {
        val id = PlayerId(UUID.randomUUID())
        val first = PlayerProfile(id, "Alice", "ja_jp", 1_000L)

        assertThat(PostgresPlayerProfileRepository(db).insertIfAbsent(first)).isTrue
        // A "restarted" server builds a fresh repository over the same database.
        val reborn = PostgresPlayerProfileRepository(db)
        assertThat(reborn.insertIfAbsent(first.copy(displayName = "Mallory"))).isFalse
        assertThat(reborn.find(id)).isEqualTo(first)
    }

    @Test
    public fun `xp accumulates atomically and persists`() {
        val id = PlayerId(UUID.randomUUID())
        val repo = PostgresPlayerXpRepository(db)

        assertThat(repo.totalXp(id)).isEqualTo(0L)
        assertThat(repo.addXp(id, 100L)).isEqualTo(100L)
        assertThat(repo.addXp(id, 50L)).isEqualTo(150L)
        assertThat(PostgresPlayerXpRepository(db).totalXp(id)).isEqualTo(150L)
    }
}
