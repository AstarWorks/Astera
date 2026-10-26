package com.astarworks.astera.core.application.service.entity

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.entity.IEntityRegistry
import com.astarworks.astera.core.application.port.outbound.entity.IEntitySpawner
import com.astarworks.astera.core.application.port.outbound.entity.INpcAdapter
import com.astarworks.astera.core.application.port.outbound.entity.INpcInteractionRouter
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityCategory
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.event.EntityDespawned
import com.astarworks.astera.core.domain.event.EntitySpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Routes NPCs to the single backend and tracks generic mob/projectile handles. */
public class RouterEntitySpawner(
    private val npcAdapter: INpcAdapter,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
    private val registry: IEntityRegistry,
    private val interactionRouter: INpcInteractionRouter,
) : IEntitySpawner {

    /**
     * Backend-id → "is this handle ours" lookup. We need this for `despawn`
     * / `teleport` because mob + projectile bookkeeping happens here (not in
     * an adapter).
     */
    private val mobHandles: ConcurrentHashMap<EntityHandle, MobEntry> = ConcurrentHashMap()
    private val projectileHandles: ConcurrentHashMap<EntityHandle, ProjectileEntry> = ConcurrentHashMap()

    /** Backend tag used for handles minted by this router's mob fallback. */
    private val mobBackendId: String = "router-mob"

    /** Backend tag used for handles minted by this router's projectile fallback. */
    private val projectileBackendId: String = "router-projectile"

    private data class MobEntry(val world: WorldId, var position: Vec3, val spec: SpawnSpec.Mob)
    private data class ProjectileEntry(val world: WorldId, var position: Vec3, val spec: SpawnSpec.Projectile)

    override fun spawn(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<EntityHandle, SpawnError> {
        // Resolve type-keyed specs against the registry first so an
        // UnknownTypeKey failure is surfaced before adapter dispatch.
        val keyToValidate: String? = when (spec) {
            is SpawnSpec.Mob -> spec.typeKey
            is SpawnSpec.Projectile -> spec.typeKey
            else -> null
        }
        if (keyToValidate != null && registry.resolve(keyToValidate) == null) {
            return Result.failure(SpawnError.UnknownTypeKey)
        }

        val spawnResult: Result<Pair<EntityHandle, EntityCategory>, SpawnError> = when (spec) {
            is SpawnSpec.DisplayNpc,
            is SpawnSpec.PlayerNpc -> dispatchToNpcAdapter(world, position, spec)

            is SpawnSpec.Mob -> {
                val handle = EntityHandle(id = UUID.randomUUID(), backendId = mobBackendId)
                mobHandles[handle] = MobEntry(world, position, spec)
                Result.success(handle to EntityCategory.MOB)
            }

            is SpawnSpec.Projectile -> {
                val handle = EntityHandle(id = UUID.randomUUID(), backendId = projectileBackendId)
                projectileHandles[handle] = ProjectileEntry(world, position, spec)
                Result.success(handle to EntityCategory.PROJECTILE)
            }
        }

        return spawnResult.onSuccess { (handle, category) ->
            eventBus.publish(
                EntitySpawned(
                    handle = handle,
                    world = world,
                    position = position,
                    category = category,
                    atTick = scheduler.currentTick(),
                ),
            )
        }.map { (handle, _) -> handle }
    }

    private fun dispatchToNpcAdapter(
        world: WorldId,
        position: Vec3,
        spec: SpawnSpec,
    ): Result<Pair<EntityHandle, EntityCategory>, SpawnError> {
        return npcAdapter.spawn(world, position, spec).map { handle -> handle to EntityCategory.NPC }
    }

    override fun despawn(handle: EntityHandle): Result<Unit, SpawnError> {
        // 1) NPC adapter ownership.
        val adapter = npcAdapterOwning(handle)
        if (adapter != null) {
            return adapter.despawn(handle).onSuccess {
                interactionRouter.unbind(handle)
                publishDespawn(handle, EntityDespawned.Reason.EXPLICIT)
            }
        }
        // 2) Router-owned mob / projectile.
        if (mobHandles.remove(handle) != null || projectileHandles.remove(handle) != null) {
            publishDespawn(handle, EntityDespawned.Reason.EXPLICIT)
            return Result.success(Unit)
        }
        return Result.failure(SpawnError.UnknownTypeKey)
    }

    override fun teleport(handle: EntityHandle, newPos: Vec3): Result<Unit, SpawnError> {
        mobHandles[handle]?.let { mobHandles[handle] = it.copy(position = newPos); return Result.success(Unit) }
        projectileHandles[handle]?.let { projectileHandles[handle] = it.copy(position = newPos); return Result.success(Unit) }
        if (npcAdapterOwning(handle) != null) return npcAdapter.teleport(handle, newPos)
        return Result.failure(SpawnError.UnknownTypeKey)
    }

    override fun all(): Collection<EntityHandle> {
        val result = ArrayList<EntityHandle>()
        result.addAll(mobHandles.keys)
        result.addAll(projectileHandles.keys)
        result.addAll(npcAdapter.all())
        return result
    }

    private fun npcAdapterOwning(handle: EntityHandle): INpcAdapter? =
        npcAdapter.takeIf { handle.backendId == NpcAdapter.BACKEND_ID }

    private fun publishDespawn(handle: EntityHandle, reason: EntityDespawned.Reason) {
        eventBus.publish(
            EntityDespawned(
                handle = handle,
                reason = reason,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    /** Test-only: drop all router-owned state (NPC adapters clear themselves). */
    public fun clear() {
        mobHandles.clear()
        projectileHandles.clear()
    }
}
