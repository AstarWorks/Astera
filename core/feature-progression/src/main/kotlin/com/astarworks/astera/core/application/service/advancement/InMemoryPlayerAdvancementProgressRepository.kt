package com.astarworks.astera.core.application.service.advancement

import com.astarworks.astera.core.application.port.outbound.IPlayerAdvancementProgressRepository
import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IPlayerAdvancementProgressRepository] (Wave 5p, ADR-0088).
 *
 * Default at `priority=0` in `AsteraCorePlugin`; overlaid by
 * `PostgresPlayerAdvancementProgressRepository` at `priority=100` when the
 * persistence plugin is loaded with valid env vars.
 *
 * Thread-safe via `ConcurrentHashMap`; rows are keyed by `(playerId,
 * advancementId)` pair so per-leaf sub-ids (see `ProgressAdvancementUseCase`)
 * coexist with canonical ids in the same map.
 */
public class InMemoryPlayerAdvancementProgressRepository : IPlayerAdvancementProgressRepository {

    private val store: ConcurrentHashMap<Pair<PlayerId, AdvancementId>, AdvancementProgress> =
        ConcurrentHashMap()

    override suspend fun find(playerId: PlayerId, advancementId: AdvancementId): AdvancementProgress? =
        store[playerId to advancementId]

    override suspend fun upsert(
        playerId: PlayerId,
        advancementId: AdvancementId,
        progress: AdvancementProgress,
    ) {
        store[playerId to advancementId] = progress
    }

    override suspend fun allFor(playerId: PlayerId): Map<AdvancementId, AdvancementProgress> =
        store.entries.asSequence()
            .filter { it.key.first == playerId }
            .associate { it.key.second to it.value }
}
