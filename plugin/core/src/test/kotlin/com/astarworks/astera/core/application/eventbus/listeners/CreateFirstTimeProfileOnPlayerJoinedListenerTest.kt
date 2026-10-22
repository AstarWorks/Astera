package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerProfileService
import com.astarworks.astera.core.application.port.outbound.ProfileError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.FirstTimeProfileCreated
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerProfile
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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, eventBus, scheduler)

        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
        assertThat(profileService.calls.first().playerId).isEqualTo(playerId)
        assertThat(profileService.profiles).containsKey(playerId)
    }

    @Test
    fun `handle publishes FirstTimeProfileCreated once and not again on a second join`() {
        val profileService = FakePlayerProfileService()
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, eventBus, scheduler)

        listener.handle(PlayerJoinedServer(playerId))
        listener.handle(PlayerJoinedServer(playerId))

        assertThat(eventBus.published.filterIsInstance<FirstTimeProfileCreated>()).hasSize(1)
    }

    @Test
    fun `handle tolerates a PartialGrant without rethrowing`() {
        val profileService = FakePlayerProfileService()
        profileService.nextOutcome = Result.failure(
            ProfileError.PartialGrant(playerId, listOf("default_loadout:missing")),
        )
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, eventBus, scheduler)

        // Should not throw — listener swallows + logs.
        listener.handle(PlayerJoinedServer(playerId))

        assertThat(profileService.calls).hasSize(1)
    }

    @Test
    fun `handle treats AlreadyExists as a no-op`() {
        // Existing profile (e.g. server restarted): the listener must NOT crash.
        val profileService = FakePlayerProfileService()
        profileService.nextOutcome = Result.failure(ProfileError.AlreadyExists(playerId))
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(profileService, eventBus, scheduler)

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
        val listener = CreateFirstTimeProfileOnPlayerJoinedListener(throwing, eventBus, scheduler)

        // Should not throw — listener catches + logs to keep the join alive.
        listener.handle(PlayerJoinedServer(playerId))
    }
}
