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

class EnterSpectatorUseCaseTest {

    private lateinit var sessions: InMemoryPlayerSessionRepository
    private lateinit var worldGateway: FakeWorldGateway
    private lateinit var useCase: EnterSpectatorUseCase

    private val playerId = PlayerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val matchId = MatchId("match-spec")

    @BeforeEach
    fun setUp() {
        sessions = InMemoryPlayerSessionRepository()
        worldGateway = FakeWorldGateway()
        useCase = EnterSpectatorUseCase(sessionRepository = sessions, worldGateway = worldGateway)
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

    // ─── Wave 5h: gateway wiring ─────────────────────────────────────────

    @Test
    fun `Wave 5h - happy path calls IWorldGateway spectatorSpawn after the state transition`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby))

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(worldGateway.spawnCalls).hasSize(1)
        val call = worldGateway.spawnCalls.first()
        assertThat(call.playerId).isEqualTo(playerId)
        assertThat(call.target).isEqualTo("match:${matchId.value}")
    }

    @Test
    fun `Wave 5h - gateway failure surfaces as TeleportFailed but session stays Spectator`() {
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby))
        worldGateway.spectatorSpawnResult = Result.failure(WorldGatewayError.UnknownMatch(matchId))

        val result = useCase.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat((result as Result.Failure).error).isInstanceOf(SpectatorError.TeleportFailed::class.java)
        // Session was still flipped to Spectator — caller is responsible for retry.
        val updated = sessions.find(playerId)!!
        assertThat(updated.state).isEqualTo(SessionState.Spectator)
        assertThat(updated.currentMatch).isEqualTo(matchId)
    }

    @Test
    fun `Wave 5h - legacy constructor without IWorldGateway still works`() {
        val legacy = EnterSpectatorUseCase(sessionRepository = sessions)
        sessions.upsert(PlayerSession(playerId, SessionState.Lobby))

        val result = legacy.enter(playerId, matchId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(sessions.find(playerId)!!.state).isEqualTo(SessionState.Spectator)
        // The shared gateway instance was not touched.
        assertThat(worldGateway.spawnCalls).isEmpty()
    }
}
