package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/** Outbound port: take one Astera item out of a player's inventory (Items registers the Paper impl). */
public fun interface IOwnedItemRemoval {
    /** Removes one [itemId] from the first matching slot; `false` when the player is offline or holds none. */
    public fun removeOne(playerId: PlayerId, itemId: ItemId): Boolean
}
