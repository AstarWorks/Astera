package com.astarworks.astera.persistence.repository

import com.astarworks.astera.core.domain.item.StoredItemState
import com.astarworks.astera.core.domain.player.InventoryItemSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import com.astarworks.astera.persistence.harness.PostgresIntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Round-trip IT for [PostgresPlayerInventorySnapshotRepository] (Wave 5e /
 * RTM issue #5) against real Postgres via Testcontainers. Skipped (via
 * [org.junit.jupiter.api.Assumptions]) when Docker is unavailable.
 */
public class PostgresPlayerInventorySnapshotRepositoryIT : PostgresIntegrationTestBase() {

    /**
     * Mixed `ContentItem` + `SerializedBukkit` slots in different regions
     * exercise the sealed discriminator both ways through the JSONB column.
     * `savedAt` is truncated to microsecond precision so the Postgres
     * `TIMESTAMP` round-trip is exactly equal (Postgres rounds to 6 digits;
     * `Instant.now()` carries 9).
     */
    @Test
    public fun `save + findLatest round-trips a mixed-region snapshot`() {
        val repo = PostgresPlayerInventorySnapshotRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val snap = PlayerInventorySnapshot(
            playerId = id,
            hotbar = List(9) { i ->
                when (i) {
                    0 -> InventoryItemSlot.ContentItem("astera:sword", count = 1)
                    1 -> InventoryItemSlot.VanillaItem("minecraft:cobblestone", 5, StoredItemState(damage = 3, enchants = mapOf("minecraft:unbreaking" to 2)))
                    else -> null
                }
            },
            main = List(27) { i ->
                if (i == 5) InventoryItemSlot.ContentItem("astera:potion", count = 16) else null
            },
            offhand = InventoryItemSlot.VanillaItem("minecraft:shield", 1),
            armor = List(4) { i ->
                if (i == 0) InventoryItemSlot.ContentItem("astera:helmet", count = 1) else null
            },
            version = PlayerInventorySnapshot.CURRENT_VERSION,
            savedAt = now,
        )

        repo.save(snap)
        val found = repo.findLatest(id)

        assertThat(found).isNotNull
        assertThat(found!!.playerId).isEqualTo(id)
        assertThat(found.version).isEqualTo(PlayerInventorySnapshot.CURRENT_VERSION)
        assertThat(found.savedAt).isEqualTo(now)
        assertThat(found.hotbar[0]).isEqualTo(InventoryItemSlot.ContentItem("astera:sword", 1))
        assertThat(found.hotbar[1]).isEqualTo(InventoryItemSlot.VanillaItem("minecraft:cobblestone", 5, StoredItemState(damage = 3, enchants = mapOf("minecraft:unbreaking" to 2))))
        assertThat(found.hotbar[2]).isNull()
        assertThat(found.main[5]).isEqualTo(InventoryItemSlot.ContentItem("astera:potion", 16))
        assertThat(found.offhand).isEqualTo(InventoryItemSlot.VanillaItem("minecraft:shield", 1))
        assertThat(found.armor[0]).isEqualTo(InventoryItemSlot.ContentItem("astera:helmet", 1))
    }

    @Test
    public fun `save twice for the same player keeps only the latest row`() {
        val repo = PostgresPlayerInventorySnapshotRepository(db)
        val id = PlayerId(UUID.randomUUID())
        val first = PlayerInventorySnapshot.empty(id).copy(
            hotbar = List(9) { i -> if (i == 0) InventoryItemSlot.ContentItem("astera:old", 1) else null },
            savedAt = Instant.parse("2026-05-19T10:00:00Z"),
        )
        val second = PlayerInventorySnapshot.empty(id).copy(
            hotbar = List(9) { i -> if (i == 0) InventoryItemSlot.ContentItem("astera:new", 1) else null },
            savedAt = Instant.parse("2026-05-19T11:00:00Z"),
        )

        repo.save(first)
        repo.save(second)

        val found = repo.findLatest(id)!!
        val firstSlot = found.hotbar[0] as InventoryItemSlot.ContentItem
        assertThat(firstSlot.itemId).isEqualTo("astera:new")
        assertThat(found.savedAt).isEqualTo(Instant.parse("2026-05-19T11:00:00Z"))
    }

    @Test
    public fun `delete removes the row and findLatest returns null afterwards`() {
        val repo = PostgresPlayerInventorySnapshotRepository(db)
        val id = PlayerId(UUID.randomUUID())
        repo.save(PlayerInventorySnapshot.empty(id))

        repo.delete(id)

        assertThat(repo.findLatest(id)).isNull()
    }
}
