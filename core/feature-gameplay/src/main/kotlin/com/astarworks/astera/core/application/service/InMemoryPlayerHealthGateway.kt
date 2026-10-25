package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerAttributeContainerStore
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.attribute.IAttributeContainer
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerRegenRateChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource

/**
 * In-memory [IPlayerHealthGateway] (Wave 5k / ADR-0080 + Wave 6a-redesign
 * Step 2 / ADR-0150 — Astera-internal HP via [IAttributeContainer]).
 *
 * # Storage model (post-ADR-0149)
 *
 * HP source of truth = per-player [IAttributeContainer] under the two
 * canonical keys [Attributes.CURRENT_HP] and [Attributes.MAX_HP]
 * (reserved in `Attributes.kt:16-17` since Wave 5e for exactly this
 * migration). No internal map for HP/max — the container IS the storage.
 *
 * `IPlayerAttributeContainerStore.containerFor(playerId)` lazy-creates
 * the container; reads return `null` (absent) until the first write
 * (matching the prior internal-map "no value recorded" semantic that
 * [getCurrentHealth] / [getMaxHealth] expose). Pre-Step-2 baseline:
 * the gateway held two private maps (keyed by PlayerId, valued as
 * Double) for current/max HP — that storage pattern is now forbidden
 * by Konsist Rule ADR-0150 §B.
 *
 * # Regen scheduling
 *
 * Unchanged: a repeating tick task adds `hpPerSecond / 20.0` per tick.
 * The per-tick callback now routes through [heal] → container write.
 * Wave 6a-redesign Step 3 will mirror the container value to Bukkit
 * `player.health` (clamped ≥ `MIRROR_MIN_HP = 1.0` so vanilla
 * `PlayerDeathEvent` never fires).
 *
 * # Thread-safety
 *
 * Not thread-safe by contract — Astera dispatches on Paper main thread.
 * Same as the prior implementation.
 *
 * Wave 6a-redesign Step 2 (ADR-0150).
 */
public class InMemoryPlayerHealthGateway(
    private val containerStore: IPlayerAttributeContainerStore,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    private val defaultMaxHp: Double = 20.0,
) : IPlayerHealthGateway {

    private data class RegenSlot(
        val hpPerSecond: Double,
        val ttlTicks: Int,
        val tickHandle: IScheduler.TaskHandle,
        val expiryHandle: IScheduler.TaskHandle?,
    )

    private val regen: MutableMap<PlayerId, RegenSlot> = mutableMapOf()

    override fun setMaxHealth(playerId: PlayerId, maxHp: Double): Result<Unit, HealthMutationError> {
        if (!maxHp.isFinite() || maxHp <= 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("maxHp", maxHp))
        }
        val container = containerStore.containerFor(playerId)
        val previous = readNumber(container, Attributes.MAX_HP)
        writeNumber(container, Attributes.MAX_HP, maxHp)
        // Re-clamp current HP if it now exceeds the new max.
        readNumber(container, Attributes.CURRENT_HP)?.let { current ->
            if (current > maxHp) {
                writeNumber(container, Attributes.CURRENT_HP, maxHp)
                eventBus.publish(
                    PlayerHealthChanged(
                        playerId = playerId,
                        oldHp = current,
                        newHp = maxHp,
                        source = HealthChangeSource.Other("max-health-clamp"),
                        atTick = scheduler.currentTick(),
                    ),
                )
            }
        }
        eventBus.publish(
            PlayerMaxHealthChanged(
                playerId = playerId,
                oldMaxHp = previous,
                newMaxHp = maxHp,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun setHealth(
        playerId: PlayerId,
        hp: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        if (!hp.isFinite() || hp < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("hp", hp))
        }
        val container = containerStore.containerFor(playerId)
        val effectiveMax = readNumber(container, Attributes.MAX_HP) ?: defaultMaxHp
        val clamped = hp.coerceAtMost(effectiveMax)
        val recordedPrevious = readNumber(container, Attributes.CURRENT_HP)
        writeNumber(container, Attributes.CURRENT_HP, clamped)
        // Publish whenever either (a) value changed against the recorded prior
        // or (b) this is the first explicit write (no prior recorded).
        if (recordedPrevious == null || recordedPrevious != clamped) {
            eventBus.publish(
                PlayerHealthChanged(
                    playerId = playerId,
                    oldHp = recordedPrevious ?: effectiveMax,
                    newHp = clamped,
                    source = source,
                    atTick = scheduler.currentTick(),
                ),
            )
        }
        return Result.success(Unit)
    }

    override fun heal(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        if (!amount.isFinite() || amount < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("amount", amount))
        }
        val container = containerStore.containerFor(playerId)
        val effectiveMax = readNumber(container, Attributes.MAX_HP) ?: defaultMaxHp
        val current = readNumber(container, Attributes.CURRENT_HP) ?: effectiveMax
        return setHealth(playerId, current + amount, source)
    }

    override fun damage(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        if (!amount.isFinite() || amount < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("amount", amount))
        }
        val container = containerStore.containerFor(playerId)
        val effectiveMax = readNumber(container, Attributes.MAX_HP) ?: defaultMaxHp
        val current = readNumber(container, Attributes.CURRENT_HP) ?: effectiveMax
        val next = (current - amount).coerceAtLeast(0.0)
        return setHealth(playerId, next, source)
    }

    override fun setRegenRate(
        playerId: PlayerId,
        hpPerSecond: Double,
        ttlTicks: Int,
    ): Result<Unit, HealthMutationError> {
        if (!hpPerSecond.isFinite() || hpPerSecond < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("hpPerSecond", hpPerSecond))
        }
        if (ttlTicks <= 0 && ttlTicks != Int.MAX_VALUE) {
            return Result.failure(HealthMutationError.InvalidValue("ttlTicks", ttlTicks.toDouble()))
        }
        // Cancel any prior regen for this player.
        regen.remove(playerId)?.let {
            it.tickHandle.cancel()
            it.expiryHandle?.cancel()
        }
        if (hpPerSecond == 0.0) {
            // Treat as clear-regen.
            eventBus.publish(
                PlayerRegenRateChanged(
                    playerId = playerId,
                    hpPerSecond = 0.0,
                    ttlTicks = 0,
                    atTick = scheduler.currentTick(),
                ),
            )
            return Result.success(Unit)
        }
        val perTick = hpPerSecond / 20.0
        val tickHandle = scheduler.runRepeatingTicks(initialDelayTicks = 1L, periodTicks = 1L) {
            heal(playerId, perTick, HealthChangeSource.Regen)
        }
        val expiryHandle: IScheduler.TaskHandle? =
            if (ttlTicks == Int.MAX_VALUE) {
                null
            } else {
                scheduler.runLaterTicks(ttlTicks.toLong()) {
                    clearRegen(playerId)
                }
            }
        regen[playerId] = RegenSlot(hpPerSecond, ttlTicks, tickHandle, expiryHandle)
        eventBus.publish(
            PlayerRegenRateChanged(
                playerId = playerId,
                hpPerSecond = hpPerSecond,
                ttlTicks = ttlTicks,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(Unit)
    }

    override fun clearRegen(playerId: PlayerId) {
        val slot = regen.remove(playerId) ?: return
        slot.tickHandle.cancel()
        slot.expiryHandle?.cancel()
        eventBus.publish(
            PlayerRegenRateChanged(
                playerId = playerId,
                hpPerSecond = 0.0,
                ttlTicks = 0,
                atTick = scheduler.currentTick(),
            ),
        )
    }

    override fun getCurrentHealth(playerId: PlayerId): Double? =
        readNumber(containerStore.containerFor(playerId), Attributes.CURRENT_HP)

    override fun getMaxHealth(playerId: PlayerId): Double? =
        readNumber(containerStore.containerFor(playerId), Attributes.MAX_HP)

    /** Test-only: is regen currently active for [playerId]. */
    public fun hasActiveRegen(playerId: PlayerId): Boolean = regen.containsKey(playerId)

    /** Test-only: configured regen rate (HP/s), or `null` if not active. */
    public fun currentRegenRate(playerId: PlayerId): Double? = regen[playerId]?.hpPerSecond

    // ---- Container helpers ────────────────────────────────────────────

    /**
     * Read a numeric attribute from [container]. Returns `null` when the
     * key is absent (preserves the "no value recorded" semantic that
     * [getCurrentHealth] / [getMaxHealth] expose).
     */
    private fun readNumber(
        container: IAttributeContainer,
        key: com.astarworks.astera.core.domain.attribute.AttributeKey,
    ): Double? = (container.get(key) as? AttributeValue.NumberValue)?.value

    /** Write a numeric attribute by replacing any prior value. */
    private fun writeNumber(
        container: IAttributeContainer,
        key: com.astarworks.astera.core.domain.attribute.AttributeKey,
        value: Double,
    ) {
        container.set(key, AttributeValue.NumberValue(value))
    }
}
