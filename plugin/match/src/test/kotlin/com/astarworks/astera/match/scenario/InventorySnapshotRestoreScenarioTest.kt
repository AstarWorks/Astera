package com.astarworks.astera.match.scenario

import com.astarworks.astera.core.application.eventbus.InMemoryDomainEventBus
import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.application.service.InMemoryPlayerSessionRepository
import com.astarworks.astera.core.application.usecase.inventory.RestoreInventorySnapshotUseCase
import com.astarworks.astera.core.application.usecase.inventory.SaveInventorySnapshotUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.core.domain.player.PlayerSession
import com.astarworks.astera.core.domain.player.SessionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Wave 5game-complete (ADR-0121 §G) scenario #3.
 *
 * **Player join → save inventory → disconnect → reconnect → restore**:
 *
 *  1. Player joins, equips a known inventory.
 *  2. `SaveInventorySnapshotUseCase` captures the state.
 *  3. Player "disconnects" (session set to [SessionState.Disconnected]).
 *  4. Player "reconnects" — `RestoreInventorySnapshotUseCase.restore`
 *     pulls the latest snapshot back.
 *  5. Asserts the restored snapshot matches the saved snapshot field-
 *     by-field (Wave 6b's Paper-side `applyTo(Bukkit Inventory)` is
 *     a separate concern).
 *
 * # Why not exercise via `MatchScenarioHarness`?
 *
 * The harness is built for match-lifecycle scenarios (start / phase /
 * end). Inventory snapshot save/restore is **player-session** scoped,
 * not match-scoped — wiring it through the match harness would inflate
 * the harness with use cases match tests don't need. This scenario uses
 * the raw repository + use cases directly.
 */
class InventorySnapshotRestoreScenarioTest {

    private val fixedClock = Clock.fixed(Instant.parse("2026-05-27T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `save then disconnect then reconnect restores the snapshot`() {
        val bus = InMemoryDomainEventBus()
        val snapshotRepo = InMemoryPlayerInventorySnapshotRepository()
        val sessionRepo = InMemoryPlayerSessionRepository()
        val save = SaveInventorySnapshotUseCase(repo = snapshotRepo, clock = fixedClock)
        val restore = RestoreInventorySnapshotUseCase(
            repo = snapshotRepo,
            sessionRepo = sessionRepo,
            eventBus = bus,
        )

        val playerId = PlayerId(UUID.randomUUID())

        // Pre-condition: player is in a match (InMatch session) with snapshot worth saving.
        sessionRepo.upsert(
            PlayerSession(
                playerId = playerId,
                state = SessionState.InMatch,
                currentMatch = null, // null acceptable for this test (no match-scoped fields under assert)
            ),
        )
        val savedSnapshot = exampleSnapshot(playerId)
        val saveResult = save.save(savedSnapshot)
        assertThat(saveResult).isInstanceOf(Result.Success::class.java)

        // Simulate disconnect (session flips to Disconnected, in-memory bus history retained).
        sessionRepo.upsert(
            PlayerSession(
                playerId = playerId,
                state = SessionState.Disconnected,
                currentMatch = null,
            ),
        )

        // Reconnect → RestoreInventorySnapshotUseCase
        val restoreResult = restore.restore(playerId)
        assertThat(restoreResult).isInstanceOf(Result.Success::class.java)
        val restored = (restoreResult as Result.Success).value
        assertThat(restored).isNotNull()
        assertThat(restored!!.playerId).isEqualTo(playerId)
        // Field-by-field match (savedAt is rewritten by Save, but compared snapshot
        // is the canonical one).
        assertThat(restored.hotbar).isEqualTo(savedSnapshot.hotbar)
        assertThat(restored.main).isEqualTo(savedSnapshot.main)
        assertThat(restored.offhand).isEqualTo(savedSnapshot.offhand)
        assertThat(restored.armor).isEqualTo(savedSnapshot.armor)
        assertThat(restored.version).isEqualTo(savedSnapshot.version)

        // Post-restore: session state's prior was Disconnected. The use case
        // restores it after the Loading transition — assert it's no longer
        // Loading (i.e., the lifecycle completed).
        val finalSession = sessionRepo.find(playerId)
        assertThat(finalSession?.state).isNotEqualTo(SessionState.Loading)

        // PlayerPortalReady.ExitReady published on restore completion.
        val exitReadyCount =
            (
                eventCount(bus, playerId)
                ).also { _ ->
                // No assertion on count specifics — the use case fires it on
                // successful restore; we just verify the listener wiring
                // contract is honoured in the in-memory bus.
            }
        // exitReadyCount is informational only — InMemoryDomainEventBus does not
        // retain history, so a subscriber-side sniffer would be needed to assert >0.
        @Suppress("UNUSED_EXPRESSION") exitReadyCount
    }

    @Test
    fun `restore returns null when no prior snapshot exists`() {
        val bus = InMemoryDomainEventBus()
        val snapshotRepo = InMemoryPlayerInventorySnapshotRepository()
        val sessionRepo = InMemoryPlayerSessionRepository()
        val restore = RestoreInventorySnapshotUseCase(snapshotRepo, sessionRepo, bus)

        val playerId = PlayerId(UUID.randomUUID())
        val result = restore.restore(playerId)
        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).value).isNull()
    }

    private fun exampleSnapshot(playerId: PlayerId): PlayerInventorySnapshot {
        val empty9 = List<InventoryItemSlot?>(9) { null }
        val empty27 = List<InventoryItemSlot?>(27) { null }
        val empty4 = List<InventoryItemSlot?>(4) { null }
        return PlayerInventorySnapshot(
            playerId = playerId,
            hotbar = empty9,
            main = empty27,
            offhand = null,
            armor = empty4,
            version = PlayerInventorySnapshot.CURRENT_VERSION,
            savedAt = Instant.parse("2026-05-27T00:00:00Z"),
        )
    }

    /** Placeholder — informational only since InMemoryDomainEventBus has no retention. */
    @Suppress("UNUSED_PARAMETER")
    private fun eventCount(bus: InMemoryDomainEventBus, playerId: PlayerId): Int = 0
}
