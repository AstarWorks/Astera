package com.astarworks.astera.combat.domain.resource

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player mana resource. Wave 5a introduces this as a *combat-domain*
 * resource (not a `Currency`) — mana regenerates over time, resets per match,
 * and is consumed by skill casts. Currency/Wallet has no regen semantics, so
 * folding mana into it would either pollute economy or duplicate logic.
 *
 * Worker ε fills the spending / regen logic. Phase A defines the value type
 * + companion factory so `ConsumeManaUseCase` + `InMemoryManaPoolService` +
 * `SkillSpec.manaCost` consumers compile.
 */
public data class ManaPool(
    val playerId: PlayerId,
    val current: Long,
    val max: Long,
    val regenPerSec: Double,
    val lastRegenTick: Long,
) {
    init {
        require(max >= 0) { "ManaPool.max must be >= 0: $max" }
        require(current in 0..max) { "ManaPool.current must be in [0, max=$max]: $current" }
        require(regenPerSec >= 0.0) { "ManaPool.regenPerSec must be >= 0: $regenPerSec" }
    }

    public fun canSpend(amount: Long): Boolean = amount <= current

    public fun spend(amount: Long): Result<ManaPool, ManaError> {
        require(amount >= 0) { "spend amount must be >= 0: $amount" }
        if (amount > current) return Result.failure(ManaError.Insufficient(have = current, tried = amount))
        return Result.success(copy(current = current - amount))
    }

    /**
     * Regenerate based on elapsed ticks (1 tick = 50ms). Caps at [max]. The
     * exact cadence/quantization is intentionally left to worker ε — Phase A
     * provides a baseline that's deterministic-testable.
     */
    public fun regen(elapsedTicks: Long): ManaPool {
        if (elapsedTicks <= 0 || regenPerSec == 0.0 || current >= max) return this
        val seconds = elapsedTicks / 20.0
        val gained = (regenPerSec * seconds).toLong()
        val newCurrent = (current + gained).coerceAtMost(max)
        return copy(current = newCurrent, lastRegenTick = lastRegenTick + elapsedTicks)
    }

    public companion object {
        public fun initial(playerId: PlayerId, max: Long, regenPerSec: Double, startTick: Long = 0L): ManaPool =
            ManaPool(playerId, current = max, max = max, regenPerSec = regenPerSec, lastRegenTick = startTick)
    }
}
