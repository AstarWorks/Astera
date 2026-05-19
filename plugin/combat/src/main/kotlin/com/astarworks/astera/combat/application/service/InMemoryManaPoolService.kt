package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5a [IManaPoolService] — Wave 5e rewrite: backed by
 * [IPlayerAttributeContainerStore]. There is no parallel
 * `Map<PlayerId, ManaPool>` anymore; the container is the source of truth
 * for `MANA_*` keys and the [ManaPool] facade is constructed on the fly
 * around the same container instance.
 *
 * Players are considered "initialized" if the container has a
 * `resource.mana_max` entry — call [init] before [consume] / [regen] (the
 * pre-Wave-5e contract is unchanged). Resets to `max` on plugin reload via
 * the Persistence overlay's container load (cold cache = re-init); the
 * Wave 5e Postgres carryover keeps `current` across reload.
 */
public class InMemoryManaPoolService(
    private val containerStore: IPlayerAttributeContainerStore = InMemoryPlayerAttributeContainer(),
) : IManaPoolService {

    private fun poolFor(playerId: PlayerId): ManaPool? {
        val container = containerStore.containerFor(playerId)
        // Treat absence of MANA_MAX as "not initialized" so callers can
        // distinguish first-touch from a real pool with current=0.
        if (container.number(Attributes.MANA_MAX) == null) return null
        return ManaPool(playerId, container)
    }

    override fun get(playerId: PlayerId): ManaPool? = poolFor(playerId)

    override fun consume(playerId: PlayerId, amount: Long): Result<ManaPool, ManaError> {
        val pool = poolFor(playerId)
            ?: return Result.failure(ManaError.PlayerNotInitialized(playerId))
        return pool.spend(amount)
    }

    override fun regen(playerId: PlayerId, ticksElapsed: Long): ManaPool {
        val pool = poolFor(playerId)
            ?: error("regen called for uninitialized player: $playerId")
        return pool.regen(ticksElapsed)
    }

    override fun init(playerId: PlayerId, max: Long, regenPerSec: Double): ManaPool =
        ManaPool.initial(playerId, max, regenPerSec, containerStore.containerFor(playerId))
}
