package com.astarworks.astera.core.application.service.engagement

import com.astarworks.astera.core.application.port.outbound.engagement.IEngagementSnapshotRepository
import com.astarworks.astera.core.domain.engagement.EngagementSnapshot
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Default in-memory [IEngagementSnapshotRepository] (Wave 5engage, ADR-0104).
 *
 * Registered by `AsteraCorePlugin` at priority 0. The persistence plugin
 * overlays this with `PostgresEngagementSnapshotRepository` at priority 100
 * when DB env vars are present.
 *
 * Backing map is a [ConcurrentHashMap] so the dashboard service's compute
 * thread can write while a REST handler thread reads — same pattern as
 * `InMemoryPlayerSessionRepository`.
 */
public class InMemoryEngagementSnapshotRepository : IEngagementSnapshotRepository {

    private val store: MutableMap<PlayerId, EngagementSnapshot> = ConcurrentHashMap()

    override suspend fun find(playerId: PlayerId): EngagementSnapshot? = store[playerId]

    override suspend fun upsert(snapshot: EngagementSnapshot) {
        store[snapshot.playerId] = snapshot
    }

    override suspend fun all(): List<EngagementSnapshot> = store.values.toList()
}
