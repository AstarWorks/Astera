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
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import com.astarworks.astera.testfixtures.FakePlayerProfileService
import com.astarworks.astera.testfixtures.FakeScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateFirstTimeProfileOnPlayerJoinedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())
    private val eventBus = FakeDomainEventBus()
    private val scheduler = FakeScheduler()

    @Test
    fun `handle calls createFirstTime for a fresh player`() {
        val profileService = FakePlayerProfileService(nowEpochMs = { 42L })
        val sessionRepo = InMemoryPlayerSessionRepository()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
    }

    @Test
    fun `handle skips createFirstTime under ADR-0122 idempotency for a live spectator session`() {
        // Wave E 5admin-ops (ADR-0122 §C): a non-Disconnected session means
        // the player is already tracked (e.g. earlier in this server's life);
        // a second PlayerJoinedServer is a duplicate dispatch.
        val profileService = FakePlayerProfileService()
        val sessionRepo = InMemoryPlayerSessionRepository().also {
            it.upsert(PlayerSession(playerId, SessionState.Spectator))
        }
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).isEmpty()
    }

    @Test
    fun `handle still skips when session exists but state is Disconnected (returning reconnect)`() {
        // Wave E 5admin-ops (ADR-0122 §C) §"reconnect"  — Disconnected
        // session = clean quit pending reconnect. The pre-Wave-E behaviour
        // skipped (existing session = returning player); that survives
        // unchanged. The new behaviour adds a stricter WARN log for the
        // non-Disconnected case (covered by the previous test). Either way:
        // for a session-exists-at-all player, the profile is not re-created.
        val profileService = FakePlayerProfileService()
        val sessionRepo = InMemoryPlayerSessionRepository().also {
            it.upsert(PlayerSession(playerId, SessionState.Disconnected))
        }
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, sessionRepo, eventBus, scheduler)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).isEmpty()
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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(throwing, sessionRepo, eventBus, scheduler)

        // Should not throw — listener catches + logs to keep the join alive.
        listener.handle(PlayerJoinedServer(playerId))
    }
}
