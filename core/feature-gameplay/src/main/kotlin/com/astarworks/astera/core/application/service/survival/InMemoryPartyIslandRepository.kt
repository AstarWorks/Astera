package com.astarworks.astera.core.application.service.survival

import com.astarworks.astera.core.application.port.outbound.survival.IPartyIslandRepository
import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.PartyIsland
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [IPartyIslandRepository] (Wave 5pve-survival /
 * ADR-0107).
 *
 * Default impl registered at priority=0 by `AsteraCorePlugin.onEnable`;
 * the persistence plugin overlays the Postgres-backed
 * `PostgresPartyIslandRepository` at priority=100.
 *
 * `forParty` scans every island — acceptable at Phase 1 scale (≤ a few
 * dozen active islands per server); revisit when load surfaces a hot spot.
 */
public class InMemoryPartyIslandRepository : IPartyIslandRepository {

    private val islands: ConcurrentHashMap<IslandId, PartyIsland> = ConcurrentHashMap()

    override fun find(id: IslandId): PartyIsland? = islands[id]

    override fun forParty(partyId: PartyId): PartyIsland? =
        islands.values.firstOrNull { it.partyId == partyId }

    override fun byStatus(statuses: Set<IslandStatus>): List<PartyIsland> =
        islands.values.filter { it.status in statuses }

    override fun upsert(island: PartyIsland) {
        islands[island.id] = island
    }

    override fun remove(id: IslandId) {
        islands.remove(id)
    }

    override fun all(): List<PartyIsland> = islands.values.toList()
}
