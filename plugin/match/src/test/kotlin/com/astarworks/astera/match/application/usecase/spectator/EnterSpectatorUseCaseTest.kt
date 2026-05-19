package com.astarworks.astera.match.application.usecase.spectator

import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class EnterSpectatorUseCaseTest {

    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var useCase: EnterSpectatorUseCase

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val matchId = MatchId("match-spec")

    @BeforeEach
    fun setUp() {
        sessions = InMemoryPlayerSessionRepository()
        useCase = EnterSpectatorUseCase(sessionRepository = sessions)
    }

    @Test
    fun `happy path - Lobby session transitions to Spectator with the match id`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby))

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val updated = sessions.find(playerId)
        assertThat(updated).isNotNull
        assertThat(updated!!.state).isEqualTo(SessionState.Spectator)
        assertThat(updated.currentMatch).isEqualTo(matchId)
    }

    @Test
    fun `InMatch session also transitions to Spectator (player swap)`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = MatchId("other")))

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val updated = sessions.find(playerId)!!
        assertThat(updated.state).isEqualTo(SessionState.Spectator)
        assertThat(updated.currentMatch).isEqualTo(matchId)
    }

    @Test
    fun `AlreadySpectator error when player is already in Spectator state`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Spectator, currentMatch = matchId))

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpectatorError.AlreadySpectator::class.java)
        assertThat((error as SpectatorError.AlreadySpectator).playerId).isEqualTo(playerId)
        // State is left unchanged.
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.Spectator)
    }

    @Test
    fun `SessionNotFound error when no session is tracked for the player`() {
        // No upsert.

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpectatorError.SessionNotFound::class.java)
        assertThat((error as SpectatorError.SessionNotFound).playerId).isEqualTo(playerId)
        assertThat(sessions.find(playerId)).isNull()
    }
}
