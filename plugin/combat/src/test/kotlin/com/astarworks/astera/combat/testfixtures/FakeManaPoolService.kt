package com.astarworks.astera.combat.testfixtures

import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IManaPoolService] for use case tests.
 *
 * Mirrors `InMemoryManaPoolService` semantics but exposes [clear] and
 * [prefill] so tests can seed exact pool states without going through
 * [init] -> regen / consume round trips.
 */
public class FakeManaPoolService : IManaPoolService {

    private val pools: MutableMap<PlayerId, ManaPool> = mutableMapOf()

    override fun get(playerId: PlayerId): ManaPool? = pools[playerId]

    override fun consume(playerId: PlayerId, amount: Long): Result<ManaPool, ManaError> {
        val pool = pools[playerId]
            ?: return Result.failure(ManaError.PlayerNotInitialized(playerId))
        return when (val outcome = pool.spend(amount)) {
            is Result.Success -> {
                pools[playerId] = outcome.value
                Result.success(outcome.value)
            }
            is Result.Failure -> Result.failure(outcome.error)
        }
    }

    override fun regen(playerId: PlayerId, ticksElapsed: Long): ManaPool {
        val pool = pools[playerId]
            ?: error("regen called for uninitialized player: $playerId")
        val refreshed = pool.regen(ticksElapsed)
        pools[playerId] = refreshed
        return refreshed
    }

    override fun init(playerId: PlayerId, max: Long, regenPerSec: Double): ManaPool {
        val pool = ManaPool.initial(playerId, max = max, regenPerSec = regenPerSec)
        pools[playerId] = pool
        return pool
    }

    /** Erase all stored pools (use between tests when not using `@BeforeEach`). */
    public fun clear() {
        pools.clear()
    }

    /** Insert an exact [pool] for [pool.playerId]; bypasses [init] / [consume]. */
    public fun prefill(pool: ManaPool) {
        pools[pool.playerId] = pool
    }
}
