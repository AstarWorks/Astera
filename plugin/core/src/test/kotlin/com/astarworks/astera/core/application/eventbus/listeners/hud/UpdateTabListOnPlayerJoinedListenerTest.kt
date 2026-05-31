package com.astarworks.astera.core.application.eventbus.listeners.hud

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.service.InMemoryTabList
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerQuitServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class UpdateTabListOnPlayerJoinedListenerTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val playerB = PlayerId(UUID.randomUUID())
    private val sessions = InMemoryPlayerSessionRepository().apply {
        upsert(PlayerSession(playerA, SessionState.Lobby))
        upsert(PlayerSession(playerB, SessionState.Lobby))
    }
    private val tabList = InMemoryTabList()
    private val profileService = StubProfileService(
        mapOf(playerA to "Alice", playerB to "Bob"),
    )
    private val listener = UpdateTabListOnPlayerJoinedListener(tabList, sessions, profileService)

    @Test
    fun `PlayerJoinedServer refreshes every online players tab list with full roster`() {
        listener.handle(PlayerJoinedServer(playerA))

        val entriesA = tabList.currentEntries(playerA)
        val entriesB = tabList.currentEntries(playerB)
        assertThat(entriesA).hasSize(2)
        assertThat(entriesA.map { it.name }).containsExactlyInAnyOrder("Alice", "Bob")
        assertThat(entriesB).hasSize(2)
        assertThat(entriesB.map { it.name }).containsExactlyInAnyOrder("Alice", "Bob")
    }

    @Test
    fun `PlayerQuitServer also triggers full roster refresh`() {
        listener.handle(PlayerQuitServer(playerA))
        // Both still in session repo per test setup; the listener just
        // re-reads sessions.all() — the actual quit transition is
        // owned by a sibling listener that removes from sessions repo
        // before this listener fires.
        assertThat(tabList.currentEntries(playerA)).hasSize(2)
    }

    @Test
    fun `tab list entries fall back to UUID prefix when profile missing`() {
        val unknown = PlayerId(UUID.randomUUID())
        sessions.upsert(PlayerSession(unknown, SessionState.Lobby))

        listener.handle(PlayerJoinedServer(unknown))

        val entries = tabList.currentEntries(playerA)
        assertThat(entries.map { it.name }).hasSize(3)
        // Unknown name is the 8-char UUID prefix
        assertThat(entries.map { it.name }).anyMatch { it.length == 8 }
    }

    private class StubProfileService(
        private val names: Map<PlayerId, String>,
    ) : IPlayerProfileService {
        override fun find(playerId: PlayerId): PlayerProfile? =
            names[playerId]?.let { name ->
                PlayerProfile(
                    id = playerId,
                    displayName = name,
                    locale = null,
                    joinedAtEpochMs = 0L,
                )
            }
        override fun createFirstTime(
            playerId: PlayerId,
            displayName: String,
            locale: String?,
        ): Result<PlayerProfile, ProfileError> = throw UnsupportedOperationException("not used")
    }
}
