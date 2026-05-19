package com.astarworks.astera.match.application.usecase.spectator

import com.astarworks.astera.core.application.port.outbound.WorldGatewayError
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.testfixtures.FakeWorldGateway
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class LeaveSpectatorUseCaseTest {

    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var useCase: LeaveSpectatorUseCase

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val matchId = MatchId("match-leave")

    @BeforeEach
    fun setUp() {
        sessions = InMemoryPlayerSessionRepository()
        worldGateway = FakeWorldGateway()
        useCase = LeaveSpectatorUseCase(sessionRepository = sessions, worldGateway = worldGateway)
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

    // ─── Wave 5h: gateway wiring ─────────────────────────────────────────

    @Test
    fun `Wave 5h - happy path calls IWorldGateway lobbySpawn after the state transition`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Spectator, currentMatch = matchId))

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(worldGateway.spawnCalls).hasSize(1)
        val call = worldGateway.spawnCalls.first()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.target).isEqualTo("lobby")
    }

    @Test
    fun `Wave 5h - gateway failure surfaces as TeleportFailed but session stays Lobby`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Spectator, currentMatch = matchId))
        worldGateway.lobbySpawnResult = Result.failure(WorldGatewayError.Storage("disk full"))

        val result = useCase.leave(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(SpectatorError.TeleportFailed::class.java)
        // Session was still flipped to Lobby — caller is responsible for retry.
        val updated = sessions.find(playerId)!!
        assertThat(updated.state).isEqualTo(SessionState.Lobby)
        assertThat(updated.currentMatch).isNull()
    }

    @Test
    fun `Wave 5h - legacy constructor without IWorldGateway still works`() {
        val legacy = LeaveSpectatorUseCase(sessionRepository = sessions)
        sessions.upsert(PlayerSession(playerId, SessionState.Spectator, currentMatch = matchId))

        val result = legacy.leave(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.Lobby)
        assertThat(worldGateway.spawnCalls).isEmpty()
    }
}
