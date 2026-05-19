package com.astarworks.astera.core.application.eventbus.listeners

import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RestoreInventoryOnPlayerJoinedListenerTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `handle triggers the restore use case and publishes ExitReady`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository().also {
            it.save(PlayerInventorySnapshot.empty(playerId))
        }
        val sessionRepo = InMemoryPlayerSessionRepository()
        val eventBus = FakeDomainEventBus()
        val restoreUC = RestoreInventorySnapshotUseCase(snapRepo, sessionRepo, eventBus)
        val listener = RestoreInventoryOnPlayerJoinedListener(restoreUC)

        listener.handle(PlayerJoinedServer(playerId))

        // The restore path always publishes an ExitReady when it completes,
        // even when no prior session existed (returnToLobby is true in that
        // case because the default prior state is Lobby).
        val published = eventBus.publishedOfType<PlayerPortalReady.ExitReady>()
        assertThat(published).hasSize(1)
        assertThat(published.first().playerId).isEqualTo(playerId)
        assertThat(published.first().returnToLobby).isTrue
    }

    @Test
    fun `handle tolerates a restore failure without rethrowing`() {
        val throwing = object : IPlayerInventorySnapshotRepository {
            override fun save(snapshot: PlayerInventorySnapshot) = error("not used")
            override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot =
                throw RuntimeException("postgres exploded")
            override fun delete(playerId: PlayerId) = error("not used")
        }
        val sessionRepo = InMemoryPlayerSessionRepository()
        val eventBus = FakeDomainEventBus()
        val restoreUC = RestoreInventorySnapshotUseCase(throwing, sessionRepo, eventBus)
        val listener = RestoreInventoryOnPlayerJoinedListener(restoreUC)

        // Should not throw; failure path is logged + swallowed.
        listener.handle(PlayerJoinedServer(playerId))

        // No ExitReady should have been published on the failure path.
        assertThat(eventBus.publishedOfType<PlayerPortalReady.ExitReady>()).isEmpty()
    }
}
