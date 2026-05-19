package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class InMemoryPlayerSessionRepositoryTest {

    private fun playerId(): PlayerId = PlayerId(UUID.randomUUID())

    @Test
    fun `upsert then find returns the same session`() {
        val repo = InMemoryPlayerSessionRepository()
        val id = playerId()
        val session = PlayerSession(playerId = id, state = SessionState.Lobby, currentMatch = null)

        repo.upsert(session)

        assertThat(repo.find(id)).isEqualTo(session)
    }

    @Test
    fun `find returns null when player has never been tracked`() {
        val repo = InMemoryPlayerSessionRepository()

        assertThat(repo.find(playerId())).isNull()
    }

    @Test
    fun `upsert overwrites the existing session for the same player`() {
        val repo = InMemoryPlayerSessionRepository()
        val id = playerId()
        val matchId = MatchId("m-1")

        repo.upsert(PlayerSession(id, SessionState.Lobby))
        repo.upsert(PlayerSession(id, SessionState.InMatch, currentMatch = matchId))

        val current = repo.find(id)
        assertThat(current).isNotNull
        assertThat(current!!.state).isEqualTo(SessionState.InMatch)
        assertThat(current.currentMatch).isEqualTo(matchId)
        assertThat(repo.all()).hasSize(1)
    }

    @Test
    fun `remove makes find return null`() {
        val repo = InMemoryPlayerSessionRepository()
        val id = playerId()
        repo.upsert(PlayerSession(id, SessionState.Lobby))

        repo.remove(id)

        assertThat(repo.find(id)).isNull()
    }

    @Test
    fun `remove for an untracked player is a no-op`() {
        val repo = InMemoryPlayerSessionRepository()

        // Must not throw.
        repo.remove(playerId())

        assertThat(repo.all()).isEmpty()
    }

    @Test
    fun `all returns a snapshot of every tracked session`() {
        val repo = InMemoryPlayerSessionRepository()
        val a = playerId()
        val b = playerId()
        val c = playerId()

        repo.upsert(PlayerSession(a, SessionState.Lobby))
        repo.upsert(PlayerSession(b, SessionState.InMatch, currentMatch = MatchId("m-x")))
        repo.upsert(PlayerSession(c, SessionState.Spectator, currentMatch = MatchId("m-y")))

        assertThat(repo.all()).containsExactlyInAnyOrder(
            PlayerSession(a, SessionState.Lobby),
            PlayerSession(b, SessionState.InMatch, currentMatch = MatchId("m-x")),
            PlayerSession(c, SessionState.Spectator, currentMatch = MatchId("m-y")),
        )
    }

    @Test
    fun `all returns an independent snapshot (mutations after the call do not leak)`() {
        val repo = InMemoryPlayerSessionRepository()
        val id = playerId()
        repo.upsert(PlayerSession(id, SessionState.Lobby))

        val snapshot = repo.all()
        repo.remove(id)

        assertThat(snapshot).hasSize(1)
        assertThat(repo.all()).isEmpty()
    }
}
