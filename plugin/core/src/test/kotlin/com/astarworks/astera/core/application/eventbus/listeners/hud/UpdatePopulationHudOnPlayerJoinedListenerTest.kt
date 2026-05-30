package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.HudError
import com.astarworks.astera.core.application.port.outbound.IHudGateway
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.hud.HudHandle
import com.astarworks.astera.core.domain.hud.HudLayout
import com.astarworks.astera.core.domain.hud.HudRegion
import com.astarworks.astera.core.domain.hud.ResourcePackSpec
import com.astarworks.astera.core.domain.match.MatchId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class UpdatePopulationHudOnPlayerJoinedListenerTest {

    private fun pid(): PlayerId = PlayerId(UUID.randomUUID())

    private class FakeSessions(initial: List<PlayerSession> = emptyList()) : IPlayerSessionRepository {
        private val store: MutableMap<PlayerId, PlayerSession> =
            initial.associateBy { it.playerId }.toMutableMap()
        override fun find(playerId: PlayerId): PlayerSession? = store[playerId]
        override fun upsert(session: PlayerSession) { store[session.playerId] = session }
        override fun remove(playerId: PlayerId) { store.remove(playerId) }
        override fun all(): List<PlayerSession> = store.values.toList()
    }

    private class RecordingHud : IHudGateway {
        public val pushed: MutableList<PlayerId> = mutableListOf()

        override fun showFor(playerId: PlayerId, layout: HudLayout): Result<HudHandle, HudError> {
            pushed += playerId
            return Result.Success(HudHandle(UUID.randomUUID().toString()))
        }

        override fun updateFor(handle: HudHandle, layout: HudLayout): Result<Unit, HudError> =
            Result.Success(Unit)

        override fun clearFor(playerId: PlayerId, region: HudRegion): Result<Unit, HudError> =
            Result.Success(Unit)

        override fun pushResourcePack(playerId: PlayerId, pack: ResourcePackSpec): Result<Unit, HudError> =
            Result.Success(Unit)

        override fun activeFor(playerId: PlayerId): Collection<HudHandle> = emptyList()
    }

    @Test
    fun `joined listener pushes population HUD to every lobby session`() {
        val joiner = pid()
        val lobbyA = pid()
        val lobbyB = pid()
        val inMatch = pid()
        val sessions = FakeSessions(
            listOf(
                PlayerSession(joiner, SessionState.Lobby, currentMatch = null as MatchId?),
                PlayerSession(lobbyA, SessionState.Lobby, currentMatch = null as MatchId?),
                PlayerSession(lobbyB, SessionState.Lobby, currentMatch = null as MatchId?),
                PlayerSession(inMatch, SessionState.InMatch, currentMatch = null as MatchId?),
            ),
        )
        val hud = RecordingHud()
        val listener = UpdatePopulationHudOnPlayerJoinedListener(sessions, hud)

        listener.handle(PlayerJoinedServer(joiner))

        assertThat(hud.pushed).containsExactlyInAnyOrder(joiner, lobbyA, lobbyB)
    }

    @Test
    fun `quit listener pushes population HUD only to lobby sessions excluding disconnected`() {
        val quitter = pid()
        val lobbyA = pid()
        val disconnected = pid()
        val sessions = FakeSessions(
            listOf(
                PlayerSession(quitter, SessionState.Lobby, currentMatch = null as MatchId?),
                PlayerSession(lobbyA, SessionState.Lobby, currentMatch = null as MatchId?),
                PlayerSession(disconnected, SessionState.Disconnected, currentMatch = null as MatchId?),
            ),
        )
        val hud = RecordingHud()
        val listener = UpdatePopulationHudOnPlayerQuitListener(sessions, hud)

        listener.handle(PlayerQuitServer(quitter))

        assertThat(hud.pushed).containsExactlyInAnyOrder(quitter, lobbyA)
    }

    @Test
    fun `no sessions means no pushes`() {
        val hud = RecordingHud()
        val listener = UpdatePopulationHudOnPlayerJoinedListener(FakeSessions(), hud)
        listener.handle(PlayerJoinedServer(pid()))
        assertThat(hud.pushed).isEmpty()
    }
}
