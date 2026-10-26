package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Host-side NPC lifecycle. World entities run on their Region/Entity; packets on the viewer's Entity. */
public interface INpcBodyMaterializer {
    public fun materialize(handle: EntityHandle, world: WorldId, position: Vec3, spec: SpawnSpec)
    public fun remove(handle: EntityHandle)
    public fun move(handle: EntityHandle, position: Vec3)
    public fun removeAll()
}
