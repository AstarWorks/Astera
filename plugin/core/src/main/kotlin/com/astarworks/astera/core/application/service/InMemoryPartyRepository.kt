package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [IPartyRepository] (Wave 5e).
 *
 * Parties are runtime-scoped: they disband on server restart. `forPlayer`
 * scans all parties for membership — acceptable at Phase 1 scale (≤10 active
 * parties), revisit if profile shows hot.
 */
public class InMemoryPartyRepository : IPartyRepository {

    private val parties: ConcurrentHashMap<PartyId, Party> = ConcurrentHashMap()

    override fun find(id: PartyId): Party? = parties[id]

    override fun forPlayer(playerId: PlayerId): Party? =
        parties.values.firstOrNull { playerId in it.members }

    override fun upsert(party: Party) {
        parties[party.id] = party
    }

    override fun remove(id: PartyId) {
        parties.remove(id)
    }

    override fun all(): List<Party> = parties.values.toList()
}
