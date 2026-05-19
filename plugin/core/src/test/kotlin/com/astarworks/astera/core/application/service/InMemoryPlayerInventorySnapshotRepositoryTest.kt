package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class InMemoryPlayerInventorySnapshotRepositoryTest {

    private val playerA = PlayerId(UUID.randomUUID())
    private val playerB = PlayerId(UUID.randomUUID())

    private fun snapshotFor(
        id: PlayerId,
        marker: String = "astera:test",
        savedAt: Instant = Instant.parse("2026-05-19T00:00:00Z"),
    ): PlayerInventorySnapshot = PlayerInventorySnapshot.empty(id, savedAt = savedAt).copy(
        hotbar = List(9) { i -> if (i == 0) InventoryItemSlot.ContentItem(marker, count = 1) else null },
    )

    @Test
    fun `save then findLatest returns the same snapshot`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val snap = snapshotFor(playerA)

        repo.save(snap)

        assertThat(repo.findLatest(playerA)).isEqualTo(snap)
    }

    @Test
    fun `save overwrites prior snapshot for same player`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val first = snapshotFor(playerA, marker = "astera:old")
        val second = snapshotFor(playerA, marker = "astera:new")

        repo.save(first)
        repo.save(second)

        val found = repo.findLatest(playerA)
        assertThat(found).isNotNull
        val firstSlot = found!!.hotbar.first() as InventoryItemSlot.ContentItem
        assertThat(firstSlot.itemId).isEqualTo("astera:new")
    }

    @Test
    fun `delete removes the snapshot`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        repo.save(snapshotFor(playerA))

        repo.delete(playerA)

        assertThat(repo.findLatest(playerA)).isNull()
    }

    @Test
    fun `findLatest returns null when player has no snapshot`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        assertThat(repo.findLatest(playerA)).isNull()
    }

    @Test
    fun `multiple players have independent snapshots`() {
        val repo = InMemoryPlayerInventorySnapshotRepository()
        val snapA = snapshotFor(playerA, marker = "astera:a")
        val snapB = snapshotFor(playerB, marker = "astera:b")

        repo.save(snapA)
        repo.save(snapB)

        assertThat(repo.findLatest(playerA)).isEqualTo(snapA)
        assertThat(repo.findLatest(playerB)).isEqualTo(snapB)

        repo.delete(playerA)
        assertThat(repo.findLatest(playerA)).isNull()
        assertThat(repo.findLatest(playerB)).isEqualTo(snapB)
    }
}
