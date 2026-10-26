package com.astarworks.astera.economy.application.port.outbound

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.economy.domain.marketplace.CreatorRevenue

/**
 * Outbound port for the creator-revenue-share registry (RTM #12, ADR-0035).
 *
 * Phase 1 PoC assumption: one [CreatorRevenue] per [ItemId] (single-creator
 * splits). Multi-creator distribution is Phase 4+. The Postgres impl
 * registered by `AsteraPersistencePlugin` overlays the in-memory default
 * registered by `AsteraEconomyPlugin` via the priority-based
 * [com.astarworks.astera.core.application.port.outbound.ServiceRegistry].
 */
public interface ICreatorRevenueRepository {
    public suspend fun findByItem(itemId: ItemId): CreatorRevenue?
    public suspend fun save(record: CreatorRevenue)
    public suspend fun delete(itemId: ItemId)
    public suspend fun all(): List<CreatorRevenue>
}
