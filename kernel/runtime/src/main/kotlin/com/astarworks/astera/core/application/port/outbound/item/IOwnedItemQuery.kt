package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: which Astera items a player is carrying right now. Core cannot read item stacks
 * (the item id tag belongs to the Items plugin), so Items registers the implementation; callers
 * such as the match loaner kit only need the ids.
 */
public fun interface IOwnedItemQuery {
    /** Item ids of every Astera stack in the player's inventory; empty when offline or carrying none. */
    public fun itemIds(playerId: PlayerId): List<ItemId>
}
