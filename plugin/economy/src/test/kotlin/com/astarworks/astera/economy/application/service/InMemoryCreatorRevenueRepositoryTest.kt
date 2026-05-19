package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class InMemoryCreatorRevenueRepositoryTest {

    private val itemA = ItemId("item-a")
    private val itemB = ItemId("item-b")
    private val creator = PlayerId(UUID.randomUUID())

    @Test
    fun `findByItem returns null when no record saved`() {
        val repo = InMemoryCreatorRevenueRepository()
        assertThat(repo.findByItem(itemA)).isNull()
    }

    @Test
    fun `save then findByItem round-trips the record`() {
        val repo = InMemoryCreatorRevenueRepository()
        val record = CreatorRevenue(itemA, creator, 0.20)
        repo.save(record)
        assertThat(repo.findByItem(itemA)).isEqualTo(record)
    }

    @Test
    fun `save overwrites prior record for the same itemId`() {
        val repo = InMemoryCreatorRevenueRepository()
        repo.save(CreatorRevenue(itemA, creator, 0.10))
        repo.save(CreatorRevenue(itemA, creator, 0.25))
        assertThat(repo.findByItem(itemA)?.revenueShare).isEqualTo(0.25)
    }

    @Test
    fun `delete removes the record`() {
        val repo = InMemoryCreatorRevenueRepository()
        repo.save(CreatorRevenue(itemA, creator, 0.10))
        repo.delete(itemA)
        assertThat(repo.findByItem(itemA)).isNull()
    }

    @Test
    fun `delete on missing item is a no-op`() {
        val repo = InMemoryCreatorRevenueRepository()
        repo.delete(itemA)  // should not throw
        assertThat(repo.all()).isEmpty()
    }

    @Test
    fun `all returns every saved record`() {
        val repo = InMemoryCreatorRevenueRepository()
        repo.save(CreatorRevenue(itemA, creator, 0.10))
        repo.save(CreatorRevenue(itemB, creator, 0.20))
        assertThat(repo.all()).hasSize(2)
            .extracting<ItemId> { it.itemId }
            .containsExactlyInAnyOrder(itemA, itemB)
    }
}
