package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.PlayerInventorySnapshot

/**
 * Outbound port: read / overwrite a live player's whole inventory as a
 * vendor-neutral [PlayerInventorySnapshot]. Main-thread only on Paper.
 */
public interface IPlayerInventoryGateway {
    /** Capture the player's current inventory, or `null` when the player is offline. */
    public fun capture(playerId: PlayerId): PlayerInventorySnapshot?

    /** Overwrite the player's inventory with [snapshot]. `false` when the player is offline. */
    public fun apply(snapshot: PlayerInventorySnapshot): Boolean
}
