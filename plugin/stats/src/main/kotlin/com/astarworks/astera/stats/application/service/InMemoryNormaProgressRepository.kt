package com.astarworks.astera.stats.application.service

import com.astarworks.astera.core.application.port.outbound.INormaProgressRepository
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.NormaId
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [INormaProgressRepository] — Phase 1 default registered by
 * `AsteraStatsPlugin` at `priority=0`. `AsteraPersistencePlugin` overlays a
 * Postgres impl at `priority=100` when configured (ADR-0020 / ADR-0027).
 *
 * Backed by a `ConcurrentHashMap` keyed on `(PlayerId, NormaId)`. Safe for
 * concurrent reads/writes from listeners on the event-bus dispatcher thread,
 * the command dispatcher thread, and the periodic norma-rollover scheduler.
 *
 * Wave 4.5 (R3.5): extracted from `InMemoryNormaService`'s former internal
 * `progressStore` so the service can transparently swap to Postgres when
 * Persistence is on the classpath.
 */
public class InMemoryNormaProgressRepository : INormaProgressRepository {

    private val store: ConcurrentHashMap<Pair<PlayerId, NormaId>, MissionProgress> = ConcurrentHashMap()

    override fun find(playerId: PlayerId, normaId: NormaId): MissionProgress? =
        store[playerId to normaId]

    override fun upsert(playerId: PlayerId, progress: MissionProgress) {
        store[playerId to progress.normaId] = progress
    }

    override fun allFor(playerId: PlayerId): Collection<MissionProgress> =
        store.entries
            .asSequence()
            .filter { (key, _) -> key.first == playerId }
            .map { (_, value) -> value }
            .toList()
}
