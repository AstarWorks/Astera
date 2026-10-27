package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Sets the vanilla material of one block of a match world (Paper: on the region that owns it). */
public interface IOreNodeWorld {
    public fun setBlock(world: WorldId, pos: Vec3, material: String)
}
