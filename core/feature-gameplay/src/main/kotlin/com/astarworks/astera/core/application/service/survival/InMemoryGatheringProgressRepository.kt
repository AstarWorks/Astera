package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IGatheringProgressRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringProgress
import com.astarworks.astera.core.domain.survival.GatheringSpotId
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [IGatheringProgressRepository] (Wave 5pve-survival /
 * ADR-0107).
 *
 * Keyed by `(playerId, spotId)` for O(1) lookup; `forPlayer` scans the
 * value collection (Phase 1 acceptable — gather counts are small).
 */
public class InMemoryGatheringProgressRepository : IGatheringProgressRepository {

    private data class Key(val playerId: PlayerId, val spotId: GatheringSpotId)

    private val rows: ConcurrentHashMap<Key, GatheringProgress> = ConcurrentHashMap()

    override suspend fun find(playerId: PlayerId, spotId: GatheringSpotId): GatheringProgress? =
        rows[Key(playerId, spotId)]

    override suspend fun forPlayer(playerId: PlayerId): List<GatheringProgress> =
        rows.values.filter { it.playerId == playerId }

    override suspend fun upsert(progress: GatheringProgress) {
        rows[Key(progress.playerId, progress.spotId)] = progress
    }
}
