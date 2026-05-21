package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerRegenRateChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource

/**
 * In-memory [IPlayerHealthGateway] (Wave 5k, ADR-0080).
 *
 * Single-JVM default; Wave 6a `PaperPlayerHealthGateway` overlays at priority
 * 100 to write back to Bukkit attributes. Tests and Phase 1 PoC use this
 * impl directly.
 *
 * Not thread-safe by contract — Astera dispatches on the Paper main thread.
 *
 * # Regen scheduling
 *
 * [setRegenRate] starts a repeating tick task (period = 1 tick) that adds
 * `hpPerSecond / 20.0` HP per tick. A separate one-shot task at `ttlTicks`
 * cancels the regen and emits a final "regen cleared" event. For
 * `ttlTicks = Int.MAX_VALUE` no expiry task is scheduled.
 */
public class InMemoryPlayerHealthGateway(
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

    private val health: MutableMap<PlayerId, Double> = mutableMapOf()
    private val maxHealth: MutableMap<PlayerId, Double> = mutableMapOf()
    private val regen: MutableMap<PlayerId, RegenSlot> = mutableMapOf()

    override fun setMaxHealth(playerId: PlayerId, maxHp: Double): Result<Unit, HealthMutationError> {
        if (!maxHp.isFinite() || maxHp <= 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("maxHp", maxHp))
        }
        val previous = maxHealth[playerId]
        maxHealth[playerId] = maxHp
        // Re-clamp current HP if it now exceeds the new max.
        health[playerId]?.let { current ->
            if (current > maxHp) {
                health[playerId] = maxHp
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
        val effectiveMax = maxHealth[playerId] ?: defaultMaxHp
        val clamped = hp.coerceAtMost(effectiveMax)
        val recordedPrevious = health[playerId]
        health[playerId] = clamped
        // Publish whenever either (a) value changed against the recorded prior
        // or (b) this is the first explicit write (no prior recorded). The
        // second case still informs listeners that an admin/system mutation
        // happened, distinct from the implicit vanilla default.
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
        val effectiveMax = maxHealth[playerId] ?: defaultMaxHp
        val current = health[playerId] ?: effectiveMax
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
        val effectiveMax = maxHealth[playerId] ?: defaultMaxHp
        val current = health[playerId] ?: effectiveMax
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

    override fun getCurrentHealth(playerId: PlayerId): Double? = health[playerId]

    override fun getMaxHealth(playerId: PlayerId): Double? = maxHealth[playerId]

    /** Test-only: is regen currently active for [playerId]. */
    public fun hasActiveRegen(playerId: PlayerId): Boolean = regen.containsKey(playerId)

    /** Test-only: configured regen rate (HP/s), or `null` if not active. */
    public fun currentRegenRate(playerId: PlayerId): Double? = regen[playerId]?.hpPerSecond
}
