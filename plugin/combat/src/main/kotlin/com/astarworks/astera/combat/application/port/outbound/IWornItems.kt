package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.combat.domain.armor.ArmorSlot
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/** Outbound: the Astera item ids a player currently wears, by armor slot (Paper: PDC `item_id` of the four armor slots). */
public interface IWornItems {
    public fun wornItems(playerId: PlayerId): Map<ArmorSlot, ItemId>
}
