package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.survival.GatheringProgress
import com.astarworks.astera.core.domain.survival.GatheringSpotId

/**
 * Outbound port: per-`(player, spot)` harvest progress (Wave 5pve-survival /
 * ADR-0107).
 *
 * Backed in production by `gathering_progress` (V42 migration); the Core
 * default is an in-memory fake.
 */
public interface IGatheringProgressRepository {

    public suspend fun find(playerId: PlayerId, spotId: GatheringSpotId): GatheringProgress?

    /** All progress rows for [playerId] across every spot. */
    public suspend fun forPlayer(playerId: PlayerId): List<GatheringProgress>

    public suspend fun upsert(progress: GatheringProgress)
}
