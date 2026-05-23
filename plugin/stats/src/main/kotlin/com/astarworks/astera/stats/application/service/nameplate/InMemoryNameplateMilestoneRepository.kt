package com.astarworks.astera.stats.application.service.nameplate

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INameplateMilestoneRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [INameplateMilestoneRepository] (Wave 5flex Phase 1
 * fallback). `AsteraStatsPlugin` registers this at priority 0; the
 * Postgres overlay (`PostgresNameplateMilestoneRepository`, V36)
 * registers at priority 100.
 */
public class InMemoryNameplateMilestoneRepository : INameplateMilestoneRepository {

    private val store = ConcurrentHashMap<PlayerId, INameplateMilestoneRepository.Record>()

    override fun find(playerId: PlayerId): INameplateMilestoneRepository.Record? =
        store[playerId]

    override fun save(record: INameplateMilestoneRepository.Record) {
        store[record.playerId] = record
    }

    override fun delete(playerId: PlayerId) {
        store.remove(playerId)
    }
}
