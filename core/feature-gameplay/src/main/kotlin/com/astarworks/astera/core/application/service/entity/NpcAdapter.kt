package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.application.port.outbound.entity.INpcBodyMaterializer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One logical NPC catalog for both Paper/Folia and the headless runtime. */
public class NpcAdapter : INpcAdapter {
    private val handles = ConcurrentHashMap.newKeySet<EntityHandle>()
    @Volatile private var bodies: INpcBodyMaterializer? = null

    override fun bindBodies(materializer: INpcBodyMaterializer) {
        check(bodies == null && handles.isEmpty()) { "Bind NPC bodies once before spawning" }
        bodies = materializer
    }

    override fun spawn(world: WorldId, position: Vec3, spec: SpawnSpec): Result<EntityHandle, SpawnError> {
        if (spec !is SpawnSpec.DisplayNpc && spec !is SpawnSpec.PlayerNpc) return Result.failure(SpawnError.NoSpawnableBackend)
        val handle = EntityHandle(UUID.randomUUID(), BACKEND_ID)
        handles += handle
        bodies?.materialize(handle, world, position, spec)
        return Result.success(handle)
    }

    override fun despawn(handle: EntityHandle): Result<Unit, SpawnError> {
        if (!handles.remove(handle)) return Result.failure(SpawnError.UnknownTypeKey)
        bodies?.remove(handle)
        return Result.success(Unit)
    }

    override fun teleport(handle: EntityHandle, position: Vec3): Result<Unit, SpawnError> {
        if (handle !in handles) return Result.failure(SpawnError.UnknownTypeKey)
        bodies?.move(handle, position)
        return Result.success(Unit)
    }

    override fun all(): Collection<EntityHandle> = handles.toList()

    public companion object { public const val BACKEND_ID: String = "npc" }
}
