package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.HealthMutationError
import com.astarworks.astera.core.application.port.outbound.IPlayerHealthGateway
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.player.physiology.HealthChangeSource

/**
 * Minimal-fake [IPlayerHealthGateway] (Wave 5k, ADR-0080).
 *
 * Plain capture-only — no scheduler, no event publish — so use-case tests
 * that only care about "did the gateway get called" can assert on
 * [calls] / [currentHealth] / [maxHealth] without wiring an
 * [com.astarworks.astera.core.application.port.outbound.IScheduler] /
 * [com.astarworks.astera.core.application.port.outbound.IDomainEventBus].
 *
 * For end-to-end regen TTL tests use [com.astarworks.astera.core.application.service.InMemoryPlayerHealthGateway]
 * with a fake scheduler instead.
 */
public class FakePlayerHealthGateway(
    private val defaultMaxHp: Double = com.astarworks.astera.core.domain.attribute.Attributes.DEFAULT_PLAYER_MAX_HP,
) : IPlayerHealthGateway {

    public sealed interface Call {
        public data class SetMaxHealth(val playerId: PlayerId, val maxHp: Double) : Call
        public data class SetHealth(val playerId: PlayerId, val hp: Double, val source: HealthChangeSource) : Call
        public data class Heal(val playerId: PlayerId, val amount: Double, val source: HealthChangeSource) : Call
        public data class Damage(val playerId: PlayerId, val amount: Double, val source: HealthChangeSource) : Call
        public data class SetRegen(val playerId: PlayerId, val hpPerSecond: Double, val ttlTicks: Int) : Call
        public data class ClearRegen(val playerId: PlayerId) : Call
    }

    private val _calls: MutableList<Call> = mutableListOf()
    private val healthMap: MutableMap<PlayerId, Double> = mutableMapOf()
    private val maxHealthMap: MutableMap<PlayerId, Double> = mutableMapOf()
    private val activeRegen: MutableSet<PlayerId> = mutableSetOf()

    public val calls: List<Call> get() = _calls.toList()

    override fun setMaxHealth(playerId: PlayerId, maxHp: Double): Result<Unit, HealthMutationError> {
        if (!maxHp.isFinite() || maxHp <= 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("maxHp", maxHp))
        }
        _calls += Call.SetMaxHealth(playerId, maxHp)
        maxHealthMap[playerId] = maxHp
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
        _calls += Call.SetHealth(playerId, hp, source)
        val cap = maxHealthMap[playerId] ?: defaultMaxHp
        healthMap[playerId] = hp.coerceAtMost(cap)
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
        _calls += Call.Heal(playerId, amount, source)
        val cap = maxHealthMap[playerId] ?: defaultMaxHp
        val current = healthMap[playerId] ?: cap
        healthMap[playerId] = (current + amount).coerceAtMost(cap)
        return Result.success(Unit)
    }

    override fun damage(
        playerId: PlayerId,
        amount: Double,
        source: HealthChangeSource,
    ): Result<Unit, HealthMutationError> {
        if (!amount.isFinite() || amount < 0.0) {
            return Result.failure(HealthMutationError.InvalidValue("amount", amount))
        }
        _calls += Call.Damage(playerId, amount, source)
        val cap = maxHealthMap[playerId] ?: defaultMaxHp
        val current = healthMap[playerId] ?: cap
        healthMap[playerId] = (current - amount).coerceAtLeast(0.0)
        return Result.success(Unit)
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
        _calls += Call.SetRegen(playerId, hpPerSecond, ttlTicks)
        if (hpPerSecond > 0.0) activeRegen += playerId else activeRegen -= playerId
        return Result.success(Unit)
    }

    override fun clearRegen(playerId: PlayerId) {
        _calls += Call.ClearRegen(playerId)
        activeRegen -= playerId
    }

    override fun getCurrentHealth(playerId: PlayerId): Double? = healthMap[playerId]
    override fun getMaxHealth(playerId: PlayerId): Double? = maxHealthMap[playerId]

    public fun hasActiveRegen(playerId: PlayerId): Boolean = playerId in activeRegen

    public fun reset() {
        _calls.clear()
        healthMap.clear()
        maxHealthMap.clear()
        activeRegen.clear()
    }
}
