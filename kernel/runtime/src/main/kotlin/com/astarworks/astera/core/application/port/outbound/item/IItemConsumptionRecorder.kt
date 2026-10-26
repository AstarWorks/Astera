package com.astarworks.astera.core.application.port.outbound.item

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: a consumable was used up. While a player holds a match entry snapshot the match-end restore
 * would hand the item back, so Core remembers what was consumed and takes it out again after the restore
 * (a consumable bought with GOLD is spent for good, whether it was used in the lobby or in a match).
 */
public fun interface IItemConsumptionRecorder {
    public fun record(playerId: PlayerId, itemId: ItemId)
}
