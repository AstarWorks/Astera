package com.astarworks.astera.core.application.usecase.inventory

import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerSessionRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerPortalReady
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import com.astarworks.astera.testfixtures.FakeDomainEventBus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RestoreInventorySnapshotUseCaseTest {

    /**
     * Stub repo that records every `upsert` invocation so the test can prove
     * the Loading-state transition fired mid-restore even though the
     * use case is synchronous.
     */
    private class RecordingSessionRepo(initial: PlayerSession? = null) : IPlayerSessionRepository {
        private var current: PlayerSession? = initial
        val transitions: MutableList<SessionState> = mutableListOf()

        override fun find(playerId: PlayerId): PlayerSession? =
            current?.takeIf { it.playerId == playerId }

        override fun upsert(session: PlayerSession) {
            transitions += session.state
            current = session
        }

        override fun remove(playerId: PlayerId) {
            if (current?.playerId == playerId) current = null
        }

        override fun all(): List<PlayerSession> = listOfNotNull(current)
    }

    /** Throwing repo for the RestoreFailed path. */
    private class ThrowingSnapshotRepo : IPlayerInventorySnapshotRepository {
        override fun save(snapshot: PlayerInventorySnapshot) = error("not used")
        override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot =
            throw RuntimeException("postgres exploded")
        override fun delete(playerId: PlayerId) = error("not used")
    }

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `restore returns null when no snapshot exists`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository()
        val sessionRepo = RecordingSessionRepo()
        val eventBus = FakeDomainEventBus()
        val usecase = RestoreInventorySnapshotUseCase(snapRepo, sessionRepo, eventBus)

        val result = usecase.restore(playerId)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `restore transitions session through Loading then back to prior state`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository().also {
            it.save(PlayerInventorySnapshot.empty(playerId))
        }
        val prior = PlayerSession(playerId, SessionState.InMatch)
        val sessionRepo = RecordingSessionRepo(initial = prior)
        val eventBus = FakeDomainEventBus()
        val usecase = RestoreInventorySnapshotUseCase(snapRepo, sessionRepo, eventBus)

        usecase.restore(playerId)

        // First transition must be Loading; last must be the restored prior state.
        assertThat(sessionRepo.transitions).hasSize(2)
        assertThat(sessionRepo.transitions[0]).isEqualTo(SessionState.Loading)
        assertThat(sessionRepo.transitions[1]).isEqualTo(SessionState.InMatch)
    }

    @Test
    fun `restore publishes ExitReady with returnToLobby=true when prior state was Lobby`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository()
        val sessionRepo = RecordingSessionRepo(initial = PlayerSession(playerId, SessionState.Lobby))
        val eventBus = FakeDomainEventBus()
        val usecase = RestoreInventorySnapshotUseCase(snapRepo, sessionRepo, eventBus)

        usecase.restore(playerId)

        val published = eventBus.publishedOfType<PlayerPortalReady.ExitReady>()
        assertThat(published).hasSize(1)
        assertThat(published.first().playerId).isEqualTo(playerId)
        assertThat(published.first().returnToLobby).isTrue
    }

    @Test
    fun `restore publishes ExitReady with returnToLobby=false when prior state was InMatch`() {
        val snapRepo = InMemoryPlayerInventorySnapshotRepository()
        val sessionRepo = RecordingSessionRepo(initial = PlayerSession(playerId, SessionState.InMatch))
        val eventBus = FakeDomainEventBus()
        val usecase = RestoreInventorySnapshotUseCase(snapRepo, sessionRepo, eventBus)

        usecase.restore(playerId)

        val published = eventBus.publishedOfType<PlayerPortalReady.ExitReady>()
        assertThat(published).hasSize(1)
        assertThat(published.first().returnToLobby).isFalse
    }

    @Test
    fun `restore returns RestoreFailed when the snapshot repo throws`() {
        val sessionRepo = RecordingSessionRepo(initial = PlayerSession(playerId, SessionState.InMatch))
        val eventBus = FakeDomainEventBus()
        val usecase = RestoreInventorySnapshotUseCase(ThrowingSnapshotRepo(), sessionRepo, eventBus)

        val result = usecase.restore(playerId)

        assertThat(result).isInstanceOf(Result.Failure::class.java)
        assertThat(result.errorOrNull()).isInstanceOf(InventorySnapshotError.RestoreFailed::class.java)
        // Loading must have been unwound before bailing so the player isn't stranded.
        assertThat(sessionRepo.transitions).contains(SessionState.Loading, SessionState.InMatch)
        // No ExitReady should be published on failure.
        assertThat(eventBus.publishedOfType<PlayerPortalReady.ExitReady>()).isEmpty()
    }
}
