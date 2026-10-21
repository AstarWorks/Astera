package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.match.TeamId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only lookup: which team is a player on, in the *current* match?
 *
 * Lives in core (not combat) because Match plugin must impl it without
 * depending on Combat plugin (ADR-0022 forbids the cycle Combat → Weapons →
 * Match → Combat). Combat plugin resolves it via shared `ServiceRegistry`.
 *
 * Absent registration → no FF checks (single-player smoke test or
 * deployment without Match plugin). Returning `null` for a particular
 * player → that player is not currently in a match.
 */
public fun interface IMatchTeamLookup {
    public fun teamOf(playerId: PlayerId): TeamId?
}
