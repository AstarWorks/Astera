package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: how many units of one Astera item a player carries, and taking several at once
 * (the in-match ore shop pays in stackable ore items). Items registers the Paper impl; the simulator
 * registers its own fake.
 */
public interface IOwnedItemStock {
    /** Total units of [itemId] across the player's inventory; 0 when offline or carrying none. */
    public fun count(playerId: PlayerId, itemId: ItemId): Int

    /** Removes exactly [amount] units of [itemId], or nothing at all; `false` when fewer are carried. */
    public fun remove(playerId: PlayerId, itemId: ItemId, amount: Int): Boolean
}
