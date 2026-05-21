package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.testfixtures.FakePlayerProfileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateFirstTimeProfileOnPlayerJoinedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `handle calls createFirstTime for a fresh player`() {
        val profileService = FakePlayerProfileService(nowEpochMs = { 42L })
        val sessionRepo = InMemoryPlayerSessionRepository()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
        assertThat(profileService.calls.first().playerId).isEqualTo(playerId)
        assertThat(profileService.profiles).containsKey(playerId)
    }

    @Test
    fun `handle skips createFirstTime when a session already exists`() {
        val profileService = FakePlayerProfileService()
        val sessionRepo = InMemoryPlayerSessionRepository().also {
            it.upsert(PlayerSession(playerId, SessionState.Lobby))
        }
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).isEmpty()
    }

    @Test
    fun `handle tolerates a PartialGrant without rethrowing`() {
        val profileService = FakePlayerProfileService()
        profileService.nextOutcome = Result.failure(
            ProfileError.PartialGrant(playerId, listOf("default_loadout:missing")),
        )
        val sessionRepo = InMemoryPlayerSessionRepository()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo)

        // Should not throw — listener swallows + logs.
        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
    }

    @Test
    fun `handle treats AlreadyExists as a no-op when session lookup said fresh`() {
        // Race scenario: an existing Postgres-backed profile but no in-memory
        // session (e.g. server just restarted). The listener must NOT crash.
        val profileService = FakePlayerProfileService()
        profileService.nextOutcome = Result.failure(ProfileError.AlreadyExists(playerId))
        val sessionRepo = InMemoryPlayerSessionRepository()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
    }

    @Test
    fun `handle does not rethrow when the profile service throws`() {
        val throwing = object : IPlayerProfileService {
            override fun find(playerId: PlayerId): PlayerProfile? = null
            override fun createFirstTime(
                playerId: PlayerId,
                displayName: String,
                locale: String?,
            ): Result<PlayerProfile, ProfileError> = throw RuntimeException("service exploded")
        }
        val sessionRepo = InMemoryPlayerSessionRepository()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(throwing, sessionRepo)

        // Should not throw — listener catches + logs to keep the join alive.
        listener.handle(PlayerJoinedServer(playerId))
    }
}
