package com.astarworks.astera.combat.domain.resource

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeKey
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.attribute.SimpleAttributeContainer
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player mana resource — Wave 5e facade over [IAttributeContainer]
 * (ADR-0058 §5).
 *
 * `current` / `max` / `regenPerSec` / `lastRegenTick` are computed getters
 * over four well-known [AttributeKey]s
 * ([Attributes.MANA_CURRENT] / [Attributes.MANA_MAX] /
 * [Attributes.MANA_REGEN_PER_SEC] / [Attributes.MANA_LAST_REGEN_TICK]).
 * Mutations (`spend` / `regen`) write back through `container.set` and
 * return `this` because the container is the source of truth.
 *
 * Construction always takes a container — use [ManaPool.initial] to seed
 * a fresh pool with `current = max` and the four keys populated.
 */
public class ManaPool(
    public val playerId: PlayerId,
    public val container: IAttributeContainer,
) {
    public val current: Long get() = container.effectiveNumber(Attributes.MANA_CURRENT).toLong()
    public val max: Long get() = container.effectiveNumber(Attributes.MANA_MAX).toLong()
    public val regenPerSec: Double get() = container.effectiveNumber(Attributes.MANA_REGEN_PER_SEC)
    public val lastRegenTick: Long get() = container.effectiveNumber(Attributes.MANA_LAST_REGEN_TICK).toLong()

    public fun canSpend(amount: Long): Boolean = amount <= current

    public fun spend(amount: Long): Result<ManaPool, ManaError> {
        require(amount >= 0) { "spend amount must be >= 0: $amount" }
        val have = current
        if (amount > have) return Result.failure(ManaError.Insufficient(have = have, tried = amount))
        write(Attributes.MANA_CURRENT, (have - amount).toDouble())
        return Result.success(this)
    }

    /**
     * Regenerate based on elapsed ticks (1 tick = 50ms). Caps at [max]. The
     * exact cadence/quantization mirrors the pre-Wave-5e value-object impl —
     * `seconds = elapsedTicks / 20.0; gained = floor(regenPerSec * seconds)`.
     * Returns `this` (mutating container under the hood).
     */
    public fun regen(elapsedTicks: Long): ManaPool {
        val rate = regenPerSec
        val cap = max
        val have = current
        if (elapsedTicks <= 0 || rate == 0.0 || have >= cap) return this
        val seconds = elapsedTicks / 20.0
        val gained = (rate * seconds).toLong()
        val newCurrent = (have + gained).coerceAtMost(cap)
        write(Attributes.MANA_CURRENT, newCurrent.toDouble())
        write(Attributes.MANA_LAST_REGEN_TICK, (lastRegenTick + elapsedTicks).toDouble())
        return this
    }

    private fun write(key: AttributeKey, value: Double) {
        container.set(key, AttributeValue.NumberValue(value))
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ManaPool) return false
        return playerId == other.playerId && container === other.container
    }

    override fun hashCode(): Int = playerId.hashCode()

    override fun toString(): String =
        "ManaPool(playerId=$playerId, current=$current, max=$max, regenPerSec=$regenPerSec)"

    public companion object {
        /**
         * Seed a fresh pool with `current = max` over a caller-provided container.
         * The four MANA_* keys are populated; the same container can also carry
         * `STAT_*` / `WALLET_*` for the same player when they all share one
         * container instance.
         */
        public fun initial(
            playerId: PlayerId,
            max: Long,
            regenPerSec: Double,
            container: IAttributeContainer,
            startTick: Long = 0L,
        ): ManaPool {
            require(max >= 0) { "ManaPool.max must be >= 0: $max" }
            require(regenPerSec >= 0.0) { "ManaPool.regenPerSec must be >= 0: $regenPerSec" }
            container.set(Attributes.MANA_MAX, AttributeValue.NumberValue(max.toDouble()))
            container.set(Attributes.MANA_CURRENT, AttributeValue.NumberValue(max.toDouble()))
            container.set(Attributes.MANA_REGEN_PER_SEC, AttributeValue.NumberValue(regenPerSec))
            container.set(Attributes.MANA_LAST_REGEN_TICK, AttributeValue.NumberValue(startTick.toDouble()))
            return ManaPool(playerId, container)
        }

        /**
         * Convenience overload that constructs a fresh [SimpleAttributeContainer].
         * Use this in tests and standalone scenarios. Production code should call
         * the container-injecting overload so player resources stay co-located.
         */
        public fun initial(
            playerId: PlayerId,
            max: Long,
            regenPerSec: Double,
            startTick: Long = 0L,
        ): ManaPool = initial(playerId, max, regenPerSec, SimpleAttributeContainer(), startTick)
    }
}
