package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Round-trip IT for [PostgresPlayerSessionRepository] (Wave _persist-1 /
 * ADR-0068) against real Postgres via Testcontainers. Skipped (via
 * [org.junit.jupiter.api.Assumptions]) when Docker is unavailable.
 */
public class PostgresPlayerSessionRepositoryIT : PostgresIntegrationTestBase() {

    @Test
    public fun `upsert + find round-trips a Lobby session with no current match`() {
        val repo = PostgresPlayerSessionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val session = PlayerSession(playerId = id, state = SessionState.Lobby, currentMatch = null)

        repo.upsert(session)
        val found = repo.find(id)

        assertThat(found).isNotNull
        assertThat(found!!.playerId).isEqualTo(id)
        assertThat(found.state).isEqualTo(SessionState.Lobby)
        assertThat(found.currentMatch).isNull()
    }

    @Test
    public fun `upsert + find round-trips an InMatch session with a MatchId`() {
        val repo = PostgresPlayerSessionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val match = MatchId("arena-final-1")
        val session = PlayerSession(playerId = id, state = SessionState.InMatch, currentMatch = match)

        repo.upsert(session)
        val found = repo.find(id)

        assertThat(found).isNotNull
        assertThat(found!!.state).isEqualTo(SessionState.InMatch)
        assertThat(found.currentMatch).isEqualTo(match)
    }

    @Test
    public fun `upsert + find round-trips every SessionState variant`() {
        val repo = PostgresPlayerSessionRepository(db)
        val variants = listOf(
            SessionState.Lobby,
            SessionState.InMatch,
            SessionState.Spectator,
            SessionState.Dead,
            SessionState.Disconnected,
            SessionState.Loading,
        )

        variants.forEach { state ->
            val id = PlayerId(UUID.randomUUID())
            repo.upsert(PlayerSession(id, state, currentMatch = null))
            assertThat(repo.find(id)?.state).isEqualTo(state)
        }
    }

    @Test
    public fun `upsert overrides the prior state for the same player`() {
        val repo = PostgresPlayerSessionRepository(db)
        val id = PlayerId(UUID.randomUUID())

        repo.upsert(PlayerSession(id, SessionState.Lobby, currentMatch = null))
        repo.upsert(PlayerSession(id, SessionState.InMatch, currentMatch = MatchId("arena-1")))

        val found = repo.find(id)!!
        assertThat(found.state).isEqualTo(SessionState.InMatch)
        assertThat(found.currentMatch).isEqualTo(MatchId("arena-1"))
    }

    @Test
    public fun `find returns null for unknown player`() {
        val repo = PostgresPlayerSessionRepository(db)
        assertThat(repo.find(PlayerId(UUID.randomUUID()))).isNull()
    }

    @Test
    public fun `all returns every persisted session`() {
        val repo = PostgresPlayerSessionRepository(db)
        val a = PlayerId(UUID.randomUUID())
        val b = PlayerId(UUID.randomUUID())
        val c = PlayerId(UUID.randomUUID())

        repo.upsert(PlayerSession(a, SessionState.Lobby, currentMatch = null))
        repo.upsert(PlayerSession(b, SessionState.InMatch, currentMatch = MatchId("arena-b")))
        repo.upsert(PlayerSession(c, SessionState.Spectator, currentMatch = MatchId("arena-c")))

        val all = repo.all()

        // Subset assertion: PostgresIntegrationTestBase shares a container across
        // sibling tests in this class, so `all()` may include sessions inserted
        // by other tests. Verify our 3 rows are present without forbidding extras.
        assertThat(all.map { it.playerId }).contains(a, b, c)
        val byId = all.associateBy { it.playerId }
        assertThat(byId[a]!!.state).isEqualTo(SessionState.Lobby)
        assertThat(byId[b]!!.state).isEqualTo(SessionState.InMatch)
        assertThat(byId[b]!!.currentMatch).isEqualTo(MatchId("arena-b"))
        assertThat(byId[c]!!.state).isEqualTo(SessionState.Spectator)
        assertThat(byId[c]!!.currentMatch).isEqualTo(MatchId("arena-c"))
    }

    @Test
    public fun `remove deletes the row`() {
        val repo = PostgresPlayerSessionRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.upsert(PlayerSession(id, SessionState.Loading, currentMatch = MatchId("arena-x")))

        repo.remove(id)

        assertThat(repo.find(id)).isNull()
    }

    @Test
    public fun `currentMatch round-trips as null and as a MatchId`() {
        val repo = PostgresPlayerSessionRepository(db)
        val id = PlayerId(UUID.randomUUID())

        repo.upsert(PlayerSession(id, SessionState.Lobby, currentMatch = null))
        assertThat(repo.find(id)!!.currentMatch).isNull()

        val match = MatchId("arena-rt-1")
        repo.upsert(PlayerSession(id, SessionState.InMatch, currentMatch = match))
        assertThat(repo.find(id)!!.currentMatch).isEqualTo(match)
    }
}
