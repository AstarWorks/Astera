package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Read-only lookup: the display colour (0xRRGGBB) of the team [playerId] is on in the current match.
 *
 * Implemented by the match side; presentation code (ground telegraphs) resolves it through the shared
 * `ServiceRegistry`. `null` = not in a match / no colour known (the caller falls back to a neutral colour).
 */
public fun interface ITeamColorLookup {
    public fun colorOf(playerId: PlayerId): Int?
}
