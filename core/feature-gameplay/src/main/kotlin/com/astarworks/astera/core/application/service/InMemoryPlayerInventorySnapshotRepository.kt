package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPlayerInventorySnapshotRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IPlayerInventorySnapshotRepository]
 * (Wave 5e / RTM issue #5).
 *
 * Used by unit tests + dev runs without Postgres. The V10 row is overlaid by
 * `persistence.repository.PostgresPlayerInventorySnapshotRepository` at
 * priority 100 in production.
 *
 * Latest-write-wins — there is no history; each `save` overwrites any prior
 * snapshot for the player. [PlayerInventorySnapshot] is a `data class` over
 * immutable lists, so concurrent readers see a consistent snapshot without
 * defensive copying.
 */
public class InMemoryPlayerInventorySnapshotRepository : IPlayerInventorySnapshotRepository {

    private val snapshots: ConcurrentHashMap<PlayerId, PlayerInventorySnapshot> = ConcurrentHashMap()

    override fun save(snapshot: PlayerInventorySnapshot) {
        snapshots[snapshot.playerId] = snapshot
    }

    override fun findLatest(playerId: PlayerId): PlayerInventorySnapshot? = snapshots[playerId]

    override fun delete(playerId: PlayerId) {
        snapshots.remove(playerId)
    }
}
