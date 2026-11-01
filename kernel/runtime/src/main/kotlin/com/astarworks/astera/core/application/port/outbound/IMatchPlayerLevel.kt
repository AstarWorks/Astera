package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * A player's in-match Norma level, asked by Core (the loaned field tools follow it, ADR-0319) without depending on
 * the Match plugin (ADR-0022); Match registers it. Absent registration or a player outside a Norma match: `null`.
 */
public fun interface IMatchPlayerLevel {
    public fun levelOf(playerId: PlayerId): Int?
}
