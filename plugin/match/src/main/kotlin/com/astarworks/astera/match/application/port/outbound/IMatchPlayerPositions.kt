package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.application.port.outbound.PlayerLocation
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Where a player stands right now, for pickup checks. `null` when the player cannot pick anything up: offline,
 * dead or a spectator (Paper reads the live player, so command teleports are seen at once). With
 * [includeDead] a dead or spectating player is located too (the kill burst needs the death position).
 */
public interface IMatchPlayerPositions {
    public fun positionOf(playerId: PlayerId, includeDead: Boolean = false): PlayerLocation?
}
