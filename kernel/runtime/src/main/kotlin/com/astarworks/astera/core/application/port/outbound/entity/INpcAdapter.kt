package com.astarworks.astera.core.application.port.outbound.entity

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId

/** Single NPC backend: Display bodies and packet-only players (ADR-0264). */
public interface INpcAdapter {
    public fun bindBodies(materializer: INpcBodyMaterializer)
    public fun spawn(world: WorldId, position: Vec3, spec: SpawnSpec): Result<EntityHandle, SpawnError>
    public fun despawn(handle: EntityHandle): Result<Unit, SpawnError>
    public fun teleport(handle: EntityHandle, position: Vec3): Result<Unit, SpawnError>
    public fun all(): Collection<EntityHandle>
}
