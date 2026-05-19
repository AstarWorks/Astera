package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.application.service.InMemoryPlayerAttributeContainer
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.number
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IManaPoolService] for use case tests. Wave 5e (ADR-0058 §5):
 * delegates to its own [InMemoryPlayerAttributeContainer] so the [ManaPool]
 * facade is constructed on demand around the same container instance.
 *
 * Exposes [clear] and [prefill] so tests can seed exact pool states without
 * going through [init] → regen / consume round trips.
 */
public class FakeManaPoolService : IManaPoolService {

    private val containerStore = InMemoryPlayerAttributeContainer()

    private fun poolFor(playerId: PlayerId): ManaPool? {
        val container = containerStore.containerFor(playerId)
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

    /** Erase all stored pools (use between tests when not using `@BeforeEach`). */
    public fun clear() {
        // Wipe each loaded container's MANA_* keys so subsequent get() returns null.
        containerStore.loaded().forEach { (_, container) ->
            container.remove(Attributes.MANA_CURRENT)
            container.remove(Attributes.MANA_MAX)
            container.remove(Attributes.MANA_REGEN_PER_SEC)
            container.remove(Attributes.MANA_LAST_REGEN_TICK)
        }
    }

    /** Insert an exact [pool] for [pool.playerId]; bypasses [init] / [consume]. */
    public fun prefill(pool: ManaPool) {
        val container = containerStore.containerFor(pool.playerId)
        container.set(Attributes.MANA_MAX, AttributeValue.NumberValue(pool.max.toDouble()))
        container.set(Attributes.MANA_CURRENT, AttributeValue.NumberValue(pool.current.toDouble()))
        container.set(Attributes.MANA_REGEN_PER_SEC, AttributeValue.NumberValue(pool.regenPerSec))
        container.set(Attributes.MANA_LAST_REGEN_TICK, AttributeValue.NumberValue(pool.lastRegenTick.toDouble()))
    }
}
