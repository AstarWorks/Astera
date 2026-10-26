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
import com.astarworks.astera.core.domain.entity.NpcTraitSpec
import com.astarworks.astera.core.domain.entity.SpawnError
import com.astarworks.astera.core.domain.entity.SpawnSpec
import com.astarworks.astera.core.domain.event.EntityDespawned
import com.astarworks.astera.core.domain.event.EntitySpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.world.WorldId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [IEntitySpawner] impl. Routes [SpawnSpec] variants to:
 *  - [INpcAdapter]s for ArmorStandNpc / DisplayNpc / CitizensNpc (priority
 *    order; Citizens > Minimal when both are available).
 *  - In-memory bookkeeping for Mob / Projectile in Wave 5l (Wave 6a's
 *    `PaperMobSpawner` / `PaperProjectileService` will overlay as
 *    priority-100 `IEntitySpawner` replacements).
 *
 * The router holds **no Bukkit state** — it only mints handles and
 * publishes domain events. The actual entity creation happens inside
 * each adapter's spawn method.
 *
 * Per ADR-0082 §"Adapter selection": the router asks adapters in
 * registration-priority order ("Citizens first if loaded, else Minimal").
 * The selection is by [List.firstOrNull] over a priority-sorted list, not
 * a fold; the first adapter whose `supports(spec)` returns true wins.
 *
 * # Tick attribution
 *
 * Published `EntitySpawned.atTick` / `EntityDespawned.atTick` use
 * [IScheduler.currentTick]. Wave 6a's Paper scheduler returns the server
 * tick; the in-memory test fake returns the fake tick.
 */
public class RouterEntitySpawner(
    private val npcAdapters: List<INpcAdapter>,
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
            is SpawnSpec.CitizensNpc -> spec.typeKey
            else -> null
        }
        if (keyToValidate != null && registry.resolve(keyToValidate) == null) {
            return Result.failure(SpawnError.UnknownTypeKey)
        }

        val spawnResult: Result<Pair<EntityHandle, EntityCategory>, SpawnError> = when (spec) {
            is SpawnSpec.ArmorStandNpc,
            is SpawnSpec.DisplayNpc,
            is SpawnSpec.CitizensNpc -> dispatchToNpcAdapter(world, position, spec)

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
            // Wave 5l: NPC trait application is delegated to the adapter
            // synchronously after spawn (the adapter already knows the
            // handle). Citizens-only trait variants on the minimal adapter
            // become no-ops per MinimalNpcAdapter contract.
            if (spec is SpawnSpec.CitizensNpc && spec.traits.isNotEmpty()) {
                npcAdapterOwning(handle)?.applyTraits(handle, spec.traits)
            }

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
        val adapter = npcAdapters.firstOrNull { it.supports(spec) }
            ?: return Result.failure(SpawnError.NoSpawnableBackend)
        return adapter.spawn(world, position, spec).map { handle -> handle to EntityCategory.NPC }
    }

    override fun despawn(handle: EntityHandle): Result<Unit, SpawnError> {
        // 1) NPC adapter ownership.
        val adapter = npcAdapterOwning(handle)
        if (adapter != null) {
            return adapter.despawn(handle)
                .fold(
                    onSuccess = {
                        interactionRouter.unbind(handle)
                        publishDespawn(handle, EntityDespawned.Reason.EXPLICIT)
                        Result.success(Unit)
                    },
                    onFailure = { Result.failure(SpawnError.NoSpawnableBackend) },
                )
        }
        // 2) Router-owned mob / projectile.
        if (mobHandles.remove(handle) != null || projectileHandles.remove(handle) != null) {
            publishDespawn(handle, EntityDespawned.Reason.EXPLICIT)
            return Result.success(Unit)
        }
        return Result.failure(SpawnError.UnknownTypeKey)
    }

    override fun teleport(handle: EntityHandle, newPos: Vec3): Result<Unit, SpawnError> {
        // NPC adapter handles don't move via this port in Wave 5l (the
        // adapter doesn't expose a teleport hook yet — Wave 6b's Paper
        // adapter will). Mob / projectile handles update in-memory state.
        mobHandles[handle]?.let { mobHandles[handle] = it.copy(position = newPos); return Result.success(Unit) }
        projectileHandles[handle]?.let { projectileHandles[handle] = it.copy(position = newPos); return Result.success(Unit) }
        // NPC handles: silently succeed for now (in-memory adapter has no
        // position state to update). Wave 6b extends INpcAdapter with
        // teleport().
        if (npcAdapterOwning(handle) != null) {
            return Result.success(Unit)
        }
        return Result.failure(SpawnError.UnknownTypeKey)
    }

    override fun all(): Collection<EntityHandle> {
        val result = ArrayList<EntityHandle>()
        result.addAll(mobHandles.keys)
        result.addAll(projectileHandles.keys)
        for (adapter in npcAdapters) {
            // INpcAdapter doesn't expose `all()` — adapters that want listing
            // implement it as a companion method. For Wave 5l we only need
            // mob / projectile lists; admin commands enumerate NPCs through
            // the adapter directly.
            if (adapter is MinimalNpcAdapter) result.addAll(adapter.all())
        }
        return result
    }

    private fun npcAdapterOwning(handle: EntityHandle): INpcAdapter? =
        npcAdapters.firstOrNull { it.backendId().value == handle.backendId }

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
