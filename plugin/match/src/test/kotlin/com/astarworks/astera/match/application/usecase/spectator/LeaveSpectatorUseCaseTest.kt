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

class LeaveSpectatorUseCaseTest {

    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var useCase: LeaveSpectatorUseCase

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val matchId = MatchId("match-leave")

    @BeforeEach
    fun setUp() {
        sessions = InMemoryPlayerSessionRepository()
        useCase = LeaveSpectatorUseCase(sessionRepository = sessions)
    }

    @Test
    fun `happy path - Spectator transitions to Lobby and clears currentMatch`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Spectator, currentMatch = matchId))

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val updated = sessions.find(playerId)
        assertThat(updated).isNotNull
        assertThat(updated!!.state).isEqualTo(SessionState.Lobby)
        assertThat(updated.currentMatch).isNull()
    }

    @Test
    fun `NotSpectator error when state is Lobby`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby))

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpectatorError.NotSpectator::class.java)
        assertThat((error as SpectatorError.NotSpectator).playerId).isEqualTo(playerId)
        // State unchanged.
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.Lobby)
    }

    @Test
    fun `NotSpectator error when state is InMatch`() {
        sessions.upsert(PlayerSession(playerId, SessionState.InMatch, currentMatch = matchId))

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(SpectatorError.NotSpectator::class.java)
    }

    @Test
    fun `SessionNotFound error when no session is tracked for the player`() {
        // No upsert.

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(SpectatorError.SessionNotFound::class.java)
        assertThat((error as SpectatorError.SessionNotFound).playerId).isEqualTo(playerId)
    }
}
