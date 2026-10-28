package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: extra items a match hands a participant right after their entry inventory snapshot was taken (so the
 * match-end restore takes them away again, like the loaner kit). Implemented by Match (bot weapon loadouts); Core's
 * snapshot listener calls it for every entry and every rejoin, before the loaner kit decides whether the player is armed.
 * The implementation must be idempotent for one player in one match (a rejoin re-arms the same loadout).
 */
public fun interface IMatchEntryLoadout {
    public fun grantOnEntry(playerId: PlayerId)
}
