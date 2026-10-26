package com.astarworks.astera.economy.application.service

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.economy.application.port.outbound.ICreatorRevenueRepository
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ICreatorRevenueRepository] for tests and Phase 1 default
 * registration (RTM #12, ADR-0035). The Postgres impl in `plugin:persistence`
 * overlays this at `priority=100` via `ServiceRegistry`.
 *
 * Backed by a [ConcurrentHashMap] so concurrent reads during sale processing
 * don't need external locking — writes from the in-game `/marketplace
 * register-creator` command (Phase 1) and reads from `PurchaseListingUseCase`
 * may interleave on the Bukkit main thread + async sale flow.
 */
public class InMemoryCreatorRevenueRepository : ICreatorRevenueRepository {
    private val records = ConcurrentHashMap<ItemId, CreatorRevenue>()

    override suspend fun findByItem(itemId: ItemId): CreatorRevenue? = records[itemId]

    override suspend fun save(record: CreatorRevenue) {
        records[record.itemId] = record
    }

    override suspend fun delete(itemId: ItemId) {
        records.remove(itemId)
    }

    override suspend fun all(): List<CreatorRevenue> = records.values.toList()
}
