package com.astarworks.astera.core.application.port.outbound.survival

import com.astarworks.astera.core.domain.backup.IslandId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.core.domain.survival.IslandStatus
import com.astarworks.astera.core.domain.survival.PartyIsland

/**
 * Outbound port: persistent record of [PartyIsland] aggregates (Wave
 * 5pve-survival / ADR-0107).
 *
 * Two layers:
 *  - `InMemoryPartyIslandRepository` (Core default) — non-persistent fake
 *    used by tests and by the foundation runtime when persistence is
 *    disabled.
 *  - `PostgresPartyIslandRepository` (persistence plugin, priority=100) —
 *    actual durable store via the `party_islands` table (V40 migration).
 *
 * The split mirrors [IPartyRepository] (Wave 5e ⟶ Wave _persist-1).
 */
public interface IPartyIslandRepository {

    public fun find(id: IslandId): PartyIsland?

    /** First island owned by [partyId] (foundation: at most one per party). */
    public fun forParty(partyId: PartyId): PartyIsland?

    /** All islands matching any of [statuses]. */
    public fun byStatus(statuses: Set<IslandStatus>): List<PartyIsland>

    public fun upsert(island: PartyIsland)

    public fun remove(id: IslandId)

    public fun all(): List<PartyIsland>
}
