package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/** What a digging player holds and looks at; read from the player's own thread while a dig is held. */
public interface IOreDigSight {
    /** Astera item id of the main-hand item, or null (bare hand / vanilla item). */
    public fun heldItem(player: PlayerId): ItemId?

    /** True while the player's crosshair is on the block [pos] of [world] within reach. */
    public fun aimsAt(player: PlayerId, world: WorldId, pos: Vec3): Boolean
}
