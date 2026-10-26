package com.astarworks.astera.items.application.service.refinement

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.items.application.port.outbound.IItemInstanceRepository
import com.astarworks.astera.items.domain.refinement.ItemInstance
import com.astarworks.astera.items.domain.refinement.ItemInstanceId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory default for [IItemInstanceRepository] (Wave 5refine, ADR-0100).
 *
 * Wired by `AsteraItemsPlugin` at priority 0;
 * `AsteraPersistencePlugin` overlays a Postgres impl at priority 100 via the
 * V30 `item_instances` table.
 *
 * Thread-safety: `ConcurrentHashMap` so async lookups (e.g. background
 * marketplace listing pre-render) do not race the main-thread mutation
 * point. Identical pattern to `InMemoryMarketplaceRepository` (Wave 5f).
 */
public class InMemoryItemInstanceRepository : IItemInstanceRepository {

    private val store: MutableMap<ItemInstanceId, ItemInstance> = ConcurrentHashMap()

    override suspend fun findById(id: ItemInstanceId): ItemInstance? = store[id]

    override suspend fun findByOwner(player: PlayerId): List<ItemInstance> =
        store.values.filter { it.owner == player }

    override suspend fun save(instance: ItemInstance) {
        store[instance.id] = instance
    }

    override suspend fun delete(id: ItemInstanceId) {
        store.remove(id)
    }
}
