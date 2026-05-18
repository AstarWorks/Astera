package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * Wave 5a in-memory [IManaPoolService] implementation.
 *
 * Stores per-player [ManaPool] state in a [ConcurrentHashMap] so the Paper
 * main thread + ad-hoc background tasks (HUD refresh, regen tickers) can
 * observe a consistent snapshot. Pools are *not* persisted — Wave 5e adds
 * Postgres carryover; for now mana resets to `max` on plugin reload or match
 * restart via [init].
 *
 * Spending semantics are delegated to [ManaPool.spend] so the domain rules
 * (`Insufficient` when `amount > current`) stay in the value object; this
 * service is only responsible for *which* pool to mutate and storing the new
 * snapshot. Regen is similarly delegated to [ManaPool.regen].
 */
public class InMemoryManaPoolService : IManaPoolService {

    private val pools: MutableMap<PlayerId, ManaPool> = ConcurrentHashMap()

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
}
