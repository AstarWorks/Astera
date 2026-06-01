package com.astarworks.astera.core.adapter.paper.physiology

import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.runLaterTicks
import com.astarworks.astera.core.application.port.outbound.runRepeatingTicks
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.PlayerHealthChanged
import com.astarworks.astera.core.domain.event.PlayerMaxHealthChanged
import com.astarworks.astera.core.domain.event.PlayerRegenRateChanged
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource
import org.bukkit.Server
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/**
 * Paper concrete of [IPlayerHealthGateway] (Wave 6a-2, ADR-0145) — first
 * concrete physiology adapter for the Wave 5k / ADR-0080 two-tier
 * health-modification split.
 *
 * # What this writes back to Bukkit
 *
 *  - [setHealth] / [heal] / [damage] → `Player.health = clamped`
 *  - [setMaxHealth] → `player.getAttribute(Attribute.MAX_HEALTH).baseValue = maxHp`
 *  - [setRegenRate] → repeating `IScheduler.runRepeatingTicks` task that
 *    calls [heal] every tick with `hpPerSecond / 20.0`. TTL via a one-shot
 *    `runLaterTicks` task that calls [clearRegen]. Identical scheduling
 *    shape to `InMemoryPlayerHealthGateway` so behaviour is interchangeable
 *    in tests.
 *
 * Paper 1.21+ Attribute enum: no `GENERIC_` prefix
 * (`Attribute.MAX_HEALTH` not `Attribute.GENERIC_MAX_HEALTH`) — matches
 * `PaperItemMaterializer:215-222` convention.
 *
 * # Threading
 *
 * Bukkit `Player.health` / `Player.setHealth` / `AttributeInstance.setBaseValue`
 * MUST be called on the main thread. The regen scheduler block is
 * `scheduler.runRepeatingTicks { heal(...) }` — Paper's `BukkitScheduler` /
 * `IScheduler` impl dispatches that on main. Callers from off-main paths
 * MUST route through `IScheduler.runLater(Duration.ZERO, ...)` first.
 *
 * # Offline-player semantics
 *
 * Per ADR-0080 §HealthMutationError KDoc, `BackendUnavailable("offline")`
 * is the explicit signal. Every mutation method checks
 * `server.getPlayer(playerId.uuid)` and returns `BackendUnavailable` when
 * null. Read-side methods ([getCurrentHealth] / [getMaxHealth]) return
 * `null` per the contract (null = "vanilla default applies").
 *
 * # Event publishing
 *
 * Publishes the same 3 events as `InMemoryPlayerHealthGateway` —
 * `PlayerMaxHealthChanged` / `PlayerHealthChanged` / `PlayerRegenRateChanged`
 * — so subscribers (HUD adapters, achievements, analytics) are
 * impl-agnostic. The `atTick` field uses [IScheduler.currentTick] same as
 * InMemory.
 *
 * # Registration
 *
 * Registered at priority 100 in `AsteraCorePlugin.onEnable` (overlays
 * `InMemoryPlayerHealthGateway` at priority 0). The InMemory impl remains
 * for unit tests / non-Paper deployments.
 */
public class PaperPlayerHealthGateway(
    private val server: Server,
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
) : IPlayerHealthGateway {

    private val log = LoggerFactory.getLogger(javaClass)

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
        val player = onlinePlayer(playerId) ?: return offlineFailure()
        val attribute = player.getAttribute(Attribute.MAX_HEALTH)
            ?: return Result.failure(HealthMutationError.BackendUnavailable("MAX_HEALTH attribute missing"))
        val previous = attribute.value
        attribute.baseValue = maxHp
        // Re-clamp current HP if it now exceeds the new max (Bukkit does
        // not auto-clamp; setBaseValue leaves player.health untouched).
        if (player.health > maxHp) {
            val before = player.health
            player.health = maxHp
            eventBus.publish(
                PlayerHealthChanged(
                    playerId = playerId,
                    oldHp = before,
                    newHp = maxHp,
                    source = HealthChangeSource.Other("max-health-clamp"),
                    atTick = scheduler.currentTick(),
                ),
            )
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
        val player = onlinePlayer(playerId) ?: return offlineFailure()
        val effectiveMax = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: VANILLA_DEFAULT_MAX_HP
        val clamped = hp.coerceAtMost(effectiveMax)
        val previous = player.health
        if (previous != clamped) {
            player.health = clamped
            eventBus.publish(
                PlayerHealthChanged(
                    playerId = playerId,
                    oldHp = previous,
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
        val player = onlinePlayer(playerId) ?: return offlineFailure()
        return setHealth(playerId, player.health + amount, source)
    }

    override fun damage(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        if (!amount.isFinite() || amount < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("amount", amount))
        }
        val player = onlinePlayer(playerId) ?: return offlineFailure()
        val next = (player.health - amount).coerceAtLeast(0.0)
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
            // Best-effort: per-tick heal failures (offline mid-regen) log
            // at DEBUG and continue; the next tick will retry or the
            // expiry task will clean up.
            val outcome = heal(playerId, perTick, HealthChangeSource.Regen)
            if (outcome is Result.Failure && log.isDebugEnabled) {
                log.debug("PaperPlayerHealthGateway regen tick failed for {}: {}", playerId, outcome.error)
            }
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
        onlinePlayer(playerId)?.health

    override fun getMaxHealth(playerId: PlayerId): Double? =
        onlinePlayer(playerId)?.getAttribute(Attribute.MAX_HEALTH)?.value

    private fun onlinePlayer(playerId: PlayerId): Player? = server.getPlayer(playerId.uuid)

    private fun offlineFailure(): Result<Unit, HealthMutationError> =
        Result.failure(HealthMutationError.BackendUnavailable("player offline"))

    public companion object {
        public const val VANILLA_DEFAULT_MAX_HP: Double = 20.0
    }
}
