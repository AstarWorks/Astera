package com.astarworks.astera.core.application.service.mob

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.mob.IMobAdapter
import com.astarworks.astera.core.application.port.outbound.mob.IMobRegistry
import com.astarworks.astera.core.application.port.outbound.mob.IMobSpawner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.entity.EntityHandle
import com.astarworks.astera.core.domain.event.MobSpawned
import com.astarworks.astera.core.domain.geometry.Vec3
import com.astarworks.astera.core.domain.mob.MobBackendId
import com.astarworks.astera.core.domain.mob.MobError
import com.astarworks.astera.core.domain.mob.MobTypeId
import com.astarworks.astera.core.domain.world.WorldId

/**
 * Default [IMobSpawner] impl (Wave 5pve, ADR-0085).
 *
 * Routes [spawn] calls to the highest-priority available [IMobAdapter] in
 * the [adapters] list. Selection rule:
 *
 *  1. Iterate [adapters] in registration-priority order (the constructor
 *     caller decides ordering — `CoreWiringPlayer` lists the adapters).
 *  2. Pick the first adapter whose [IMobAdapter.isAvailable] returns true.
 *  3. If none available, return [MobError.NoAdapterAvailable].
 *
 * Publishes [MobSpawned] on successful spawn so PvE subscribers (loot
 * distributor, XP distributor, analytics) observe the spawn without polling.
 *
 * # Despawn routing
 *
 * Uses [EntityHandle.backendId] to find the owning adapter — same pattern
 * as `RouterEntitySpawner` (Wave 5l ADR-0082). A handle whose backend id
 * doesn't match any registered adapter returns `NoAdapterAvailable`.
 *
 * # Why no in-router bookkeeping
 *
 * Unlike `RouterEntitySpawner` (which falls back to in-memory mob handles),
 * mob spawning ALWAYS goes through an adapter — there's no "router-owned"
 * mob category. Adapters track their own handles; the router is pure
 * dispatch + event publishing.
 */
public class MobSpawnerRouter(
    private val adapters: List<IMobAdapter>,
    private val registry: IMobRegistry,
    private val eventBus: IDomainEventBus,
    private val scheduler: IScheduler,
) : IMobSpawner {

    override fun spawn(
        world: WorldId,
        position: Vec3,
        mobTypeId: MobTypeId,
    ): Result<EntityHandle, MobError> {
        val spec = registry.find(mobTypeId)
            ?: return Result.failure(MobError.UnknownMobType)

        val adapter = selectAvailableAdapter()
            ?: return Result.failure(MobError.NoAdapterAvailable)

        return adapter.spawn(world, position, spec).onSuccess { handle ->
            eventBus.publish(
                MobSpawned(
                    handle = handle,
                    mobTypeId = spec.id,
                    world = world,
                    position = position,
                    backendId = adapter.backendId(),
                    atTick = scheduler.currentTick(),
                ),
            )
        }
    }

    override fun despawn(handle: EntityHandle): Result<Unit, MobError> {
        val adapter = adapters.firstOrNull { it.backendId().value == handle.backendId }
            ?: return Result.failure(MobError.NoAdapterAvailable)
        return adapter.despawn(handle)
    }

    override fun isAlive(handle: EntityHandle): Boolean =
        adapters.firstOrNull { it.backendId().value == handle.backendId }?.isAlive(handle) ?: false

    override fun activeBackendId(): MobBackendId? = selectAvailableAdapter()?.backendId()

    private fun selectAvailableAdapter(): IMobAdapter? =
        adapters.firstOrNull { it.isAvailable() }
}
