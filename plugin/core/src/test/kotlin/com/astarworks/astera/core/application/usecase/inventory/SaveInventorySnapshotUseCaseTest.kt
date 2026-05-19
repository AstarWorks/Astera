package com.astarworks.astera.core.application.usecase.inventory

import com.astarworks.astera.core.application.service.InMemoryPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SaveInventorySnapshotUseCaseTest {

    private val playerId = PlayerId(UUID.randomUUID())

    @Test
    fun `save returns success and persists the snapshot`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val usecase = SaveInventorySnapshotUseCase(repo)
        val snap = PlayerInventorySnapshot.empty(playerId).copy(
            hotbar = List(9) { i -> if (i == 0) InventoryItemSlot.ContentItem("astera:sword", 1) else null },
        )

        val result = usecase.save(snap)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(repo.findLatest(playerId)).isNotNull
    }

    @Test
    fun `save stamps savedAt with the injected clock`() {
        val fixed = Instant.parse("2026-05-19T12:34:56Z")
        val clock = Clock.fixed(fixed, ZoneOffset.UTC)
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val usecase = SaveInventorySnapshotUseCase(repo, clock)
        // Caller-supplied savedAt should be overridden by clock.instant().
        val callerStamp = Instant.parse("2000-01-01T00:00:00Z")
        val snap = PlayerInventorySnapshot.empty(playerId, savedAt = callerStamp)

        usecase.save(snap)

        val stored = repo.findLatest(playerId)
        assertThat(stored).isNotNull
        assertThat(stored!!.savedAt).isEqualTo(fixed)
    }

    @Test
    fun `save round-trips snapshot contents through the repo`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val usecase = SaveInventorySnapshotUseCase(repo)
        val snap = PlayerInventorySnapshot.empty(playerId).copy(
            hotbar = List(9) { i -> if (i == 0) InventoryItemSlot.ContentItem("astera:sword", 1) else null },
            offhand = InventoryItemSlot.SerializedBukkit("base64-blob"),
            armor = List(4) { i -> if (i == 0) InventoryItemSlot.ContentItem("astera:helmet", 1) else null },
        )

        usecase.save(snap)

        val stored = repo.findLatest(playerId)!!
        assertThat(stored.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("astera:sword", 1))
        assertThat(stored.offhand).isEqualTo(InventoryItemSlot.SerializedBukkit("base64-blob"))
        assertThat(stored.armor[0]).isEqualTo(InventoryItemSlot.ContentItem("astera:helmet", 1))
    }
}
