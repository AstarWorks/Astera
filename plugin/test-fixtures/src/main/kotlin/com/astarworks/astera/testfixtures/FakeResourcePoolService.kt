package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.ResourceError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.item.ItemId
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.PlayerResourcePool
import com.astarworks.astera.core.domain.resource.ResourceCap
import com.astarworks.astera.core.domain.resource.ResourceType

/**
 * Minimal fake [IResourcePoolService] (Wave 5k, ADR-0080).
 *
 * No item registry, no events — pure call-capture for use-case tests that
 * just need to verify "spend / reload / regen was called with X".
 */
public class FakeResourcePoolService(
    private val caps: Map<ResourceType, ResourceCap> = ResourceCap.DEFAULTS,
) : IResourcePoolService {

    public sealed interface Call {
        public data class Initialise(val playerId: PlayerId) : Call
        public data class SetActiveWeapon(val playerId: PlayerId, val weapon: ItemId?) : Call
        public data class Spend(val playerId: PlayerId, val type: ResourceType, val amount: Int) : Call
        public data class Reload(val playerId: PlayerId, val weapon: ItemId) : Call
        public data class Regen(val playerId: PlayerId, val type: ResourceType, val amount: Int) : Call
    }

    private val _calls: MutableList<Call> = mutableListOf()
    private val pools: MutableMap<PlayerId, PlayerResourcePool> = mutableMapOf()

    /** Test knob: which weapons are considered Ammo-reloadable. */
    public val reloadableWeapons: MutableSet<ItemId> = mutableSetOf()

    public val calls: List<Call> get() = _calls.toList()

    override fun initialiseFor(playerId: PlayerId): PlayerResourcePool {
        _calls += Call.Initialise(playerId)
        return pools.getOrPut(playerId) {
            PlayerResourcePool(
                playerId = playerId,
                activeWeapon = null,
                ammo = caps[ResourceType.Ammo]?.maxPool ?: 0,
                energy = caps[ResourceType.Energy]?.maxPool ?: 0,
            )
        }
    }

    override fun currentPool(playerId: PlayerId): PlayerResourcePool? = pools[playerId]

    override fun setActiveWeapon(playerId: PlayerId, weapon: ItemId?) {
        _calls += Call.SetActiveWeapon(playerId, weapon)
        val pool = pools[playerId] ?: initialiseFor(playerId)
        pools[playerId] = pool.copy(activeWeapon = weapon)
    }

    override fun spendResource(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError> {
        if (amount < 0) return Result.failure(ResourceError.InvalidValue("amount", amount))
        _calls += Call.Spend(playerId, type, amount)
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val available = pool.valueOf(type)
        if (available < amount) {
            return Result.failure(ResourceError.Insufficient(type, available, amount))
        }
        val remaining = available - amount
        pools[playerId] = pool.with(type, remaining)
        return Result.success(remaining)
    }

    override fun reload(playerId: PlayerId, weapon: ItemId): Result<Int, ResourceError> {
        _calls += Call.Reload(playerId, weapon)
        if (weapon !in reloadableWeapons) return Result.failure(ResourceError.NotReloadable(weapon))
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val cap = caps[ResourceType.Ammo]?.maxPool ?: 0
        pools[playerId] = pool.with(ResourceType.Ammo, cap)
        return Result.success(cap)
    }

    override fun applyRegen(
        playerId: PlayerId,
        type: ResourceType,
        amount: Int,
    ): Result<Int, ResourceError> {
        if (amount < 0) return Result.failure(ResourceError.InvalidValue("amount", amount))
        _calls += Call.Regen(playerId, type, amount)
        val pool = pools[playerId] ?: return Result.failure(ResourceError.UninitialisedPlayer)
        val cap = caps[type]?.maxPool ?: 0
        val next = (pool.valueOf(type) + amount).coerceAtMost(cap)
        pools[playerId] = pool.with(type, next)
        return Result.success(next)
    }

    override fun capFor(playerId: PlayerId, type: ResourceType): ResourceCap =
        caps[type] ?: ResourceCap.DEFAULTS.getValue(type)

    public fun reset() {
        _calls.clear()
        pools.clear()
        reloadableWeapons.clear()
    }
}
