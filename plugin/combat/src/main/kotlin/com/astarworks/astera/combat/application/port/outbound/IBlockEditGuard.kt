package com.astarworks.astera.combat.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Safety gate for skill-driven terrain edits (WP5: SetBlock / RemoveBlock /
 * block-breaking explosions). Terrain outside a match arena must never be
 * modified by a skill unless the skill explicitly opts out (`outside_arena`).
 */
public fun interface IBlockEditGuard {

    /** True when [actor]'s skills may edit the block at [pos] in [world]. */
    public fun canEdit(actor: PlayerId, world: WorldId, pos: Vec3): Boolean
}
