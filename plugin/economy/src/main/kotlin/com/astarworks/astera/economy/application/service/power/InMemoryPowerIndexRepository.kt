package com.astarworks.astera.economy.application.service.power

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.economy.application.port.outbound.IPowerIndexRepository
import com.astarworks.astera.economy.domain.power.PowerIndex
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [IPowerIndexRepository] (Wave 5flex / ADR-0102 surface #4).
 *
 * Default registration in `AsteraEconomyPlugin` at priority 0; Postgres
 * impl overlays at priority 100 when `:plugin:persistence` is loaded.
 * Concurrent map because the recalculation use case is invoked from a
 * scheduled task that may overlap with Web admin / nameplate reads on
 * the same player.
 */
public class InMemoryPowerIndexRepository : IPowerIndexRepository {

    private val snapshots = ConcurrentHashMap<PlayerId, PowerIndex>()

    override suspend fun find(playerId: PlayerId): PowerIndex? = snapshots[playerId]

    override suspend fun save(snapshot: PowerIndex) {
        snapshots[snapshot.playerId] = snapshot
    }

    override suspend fun delete(playerId: PlayerId) {
        snapshots.remove(playerId)
    }

    override suspend fun topN(n: Int): List<PowerIndex> {
        if (n <= 0) return emptyList()
        return snapshots.values
            .sortedByDescending { it.total }
            .take(n)
    }
}
