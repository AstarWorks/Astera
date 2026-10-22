package com.astarworks.astera.items.application.port.outbound

import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.lobby.TrainingRackSlot
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/** One weapon on the lobby training rack: the frame [slot] shows [itemId]. */
public data class RackFrame(val slot: TrainingRackSlot, val itemId: ItemId)

/**
 * Outbound port: the physical weapon rack (F3-11, ADR-0200). The Paper impl keeps one non-persistent
 * item frame per [RackFrame]; the simulator records them.
 */
public interface ITrainingRackGateway {
    /**
     * Makes exactly [frames] exist in [world] (missing ones are created, stray ones removed).
     * Returns how many frames are in place, or `null` when the world is not loaded yet.
     */
    public fun ensure(world: WorldId, frames: List<RackFrame>): Int?

    /** Removes every frame this gateway created. */
    public fun removeAll()
}

/** Outbound port: the lobby-only (training) stacks in a player's inventory. */
public interface ITrainingInventory {
    /** True when [playerId] carries a training stack of [itemId]. */
    public fun holds(playerId: PlayerId, itemId: ItemId): Boolean

    /** Removes every training stack from [playerId]'s inventory; returns how many stacks were removed. */
    public fun strip(playerId: PlayerId): Int

    /** Online players that currently carry at least one training stack. */
    public fun holders(): Set<PlayerId>
}
