package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.Party
import com.astarworks.astera.core.domain.social.PartyId

/**
 * Outbound port: party records (Wave 5e). In-memory only — parties are
 * runtime-scoped and disband on server restart.
 */
public interface IPartyRepository {
    public fun find(id: PartyId): Party?
    public fun forPlayer(playerId: PlayerId): Party?
    public fun upsert(party: Party)
    public fun remove(id: PartyId)
    public fun all(): List<Party>
}
