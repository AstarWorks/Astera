package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IItemRegistry
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ResourceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerActiveWeaponChanged
import com.astarworks.astera.core.domain.event.ResourceReloaded
import com.astarworks.astera.core.domain.event.ResourceSpent
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.PlayerResourcePool
import com.astarworks.astera.core.domain.resource.ResourceCap
import com.astarworks.astera.core.domain.resource.ResourceType

/**
 * In-memory [IResourcePoolService] (Wave 5k, ADR-0080).
 *
 * Single-JVM default at priority 0; Wave 6/Persistence-layer impls (if ever
 * needed — Phase 1 PoC keeps resource pools as runtime-only) may overlay at
 * priority 100.
 *
 * # Cap resolution
 *
 *   1. If [itemRegistry] returns an item whose `resourceMaxPool > 0`, use it.
 *   2. Else use the [ResourceCap.DEFAULTS] entry for the type.
 *
 * Same precedence for `resourceRegenPerSecond` (sentinel value -1.0 = "use
 * default").
 */
public class InMemoryResourcePoolService(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val itemRegistry: IItemRegistry,
    private val defaults: Map<ResourceType, ResourceCap> = ResourceCap.DEFAULTS,
) : IResourcePoolService {

    private val pools: MutableMap<PlayerId, PlayerResourcePool> = mutableMapOf()

    override fun initialiseFor(playerId: PlayerId): PlayerResourcePool {
        return pools.getOrPut(playerId) {
            PlayerResourcePool(
                playerId = playerId,
                activeWeapon = null,
                ammo = defaults[ResourceType.Ammo]?.maxPool ?: 0,
                stamina = defaults[ResourceType.Stamina]?.maxPool ?: 0,
                mana = defaults[ResourceType.Mana]?.maxPool ?: 0,
            )
        }
    }

    override fun currentPool(playerId: PlayerId): PlayerResourcePool? = pools[playerId]

    override fun setActiveWeapon(playerId: PlayerId, weapon: ItemId?) {
        val existing = pools[playerId] ?: initialiseFor(playerId)
        if (existing.activeWeapon == weapon) return
        pools[playerId] = existing.copy(activeWeapon = weapon)
        eventBus.publish(
            PlayerActiveWeaponChanged(
                playerId = playerId,
                previous = existing.activeWeapon,
                current = weapon,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun spendResource(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError> {
        if (amount < 0) return Result.failure(ResourceError.InvalidValue("amount", amount))
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val available = pool.valueOf(type)
        if (available < amount) {
            return Result.failure(ResourceError.Insufficient(type, available, amount))
        }
        val remaining = available - amount
        pools[playerId] = pool.with(type, remaining)
        eventBus.publish(
            ResourceSpent(
                playerId = playerId,
                type = type,
                amount = amount,
                remaining = remaining,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(remaining)
    }

    override fun reload(playerId: PlayerId, weapon: ItemId): Result<Int, ResourceError> {
        val spec = itemRegistry.find(weapon) ?: return Result.failure(ResourceError.UnknownWeapon(weapon))
        if (spec.resourceType != ResourceType.Ammo || !spec.resourceReloadable) {
            return Result.failure(ResourceError.NotReloadable(weapon))
        }
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val cap = resolveCap(spec.resourceType, spec.resourceMaxPool)
        pools[playerId] = pool.with(ResourceType.Ammo, cap)
        eventBus.publish(
            ResourceReloaded(
                playerId = playerId,
                weapon = weapon,
                ammoAfter = cap,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(cap)
    }

    override fun applyRegen(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError> {
        if (amount < 0) return Result.failure(ResourceError.InvalidValue("amount", amount))
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val cap = capFor(type).maxPool
        val current = pool.valueOf(type)
        val next = (current + amount).coerceAtMost(cap)
        if (next == current) return Result.success(current)
        pools[playerId] = pool.with(type, next)
        return Result.success(next)
    }

    override fun capFor(type: ResourceType): ResourceCap =
        defaults[type] ?: ResourceCap.DEFAULTS.getValue(type)

    private fun resolveCap(type: ResourceType, override: Int): Int =
        if (override > 0) override else capFor(type).maxPool
}
