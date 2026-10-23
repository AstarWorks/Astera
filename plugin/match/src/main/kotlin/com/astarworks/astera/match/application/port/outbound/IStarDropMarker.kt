package com.astarworks.astera.match.application.port.outbound

import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Makes a star drop visible on the map (Paper: a particle puff). Called every few ticks while the drop lies there. */
public interface IStarDropMarker {
    public fun mark(world: WorldId, position: Vec3)
}
