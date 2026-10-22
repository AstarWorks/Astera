package com.astarworks.astera.items.application.port.outbound

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port (ADR-0195): draw the native hotbar cooldown sweep over every
 * stack of [ItemId] the player carries. On Paper this is
 * `Player.setCooldown(Key, ticks)` on the item's `use_cooldown` group
 * (`ItemCooldownSweep.groupKey`).
 */
public interface IItemCooldownSweepGateway {

    /** Run the sweep for [ticks] ticks (`0` clears it). A no-op for offline players. */
    public fun sweep(playerId: PlayerId, itemId: ItemId, ticks: Int)
}
