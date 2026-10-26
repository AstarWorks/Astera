package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.EgoError
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IEgoGauge
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.ego.EgoConfig
import com.astarworks.astera.core.domain.event.EgoGaugeChanged
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [IEgoGauge] (ADR-0231). Out of combat = [EgoConfig.decayDelayTicks]
 * since the player last gained (dealt or took a hit); only then does [decay] drain.
 */
public class InMemoryEgoGauge(
    private val scheduler: IScheduler,
    private val eventBus: IDomainEventBus,
    override val config: EgoConfig = EgoConfig.DEFAULTS,
) : IEgoGauge {

    private val values: MutableMap<PlayerId, Int> = HashMap()
    private val lastGainTick: MutableMap<PlayerId, Long> = HashMap()

    override fun value(playerId: PlayerId): Int = values[playerId] ?: 0

    override fun gain(playerId: PlayerId, amount: Int): Int {
        val before = value(playerId)
        if (amount <= 0) return before
        val now = scheduler.currentTick()
        lastGainTick[playerId] = now
        val after = (before + amount).coerceAtMost(config.max)
        set(playerId, before, after, now)
        return after
    }

    override fun spend(playerId: PlayerId, cost: Int): Result<Int, EgoError> {
        if (cost < 0) return Result.failure(EgoError.InvalidValue(cost))
        val before = value(playerId)
        if (before < cost) return Result.failure(EgoError.Insufficient(before, cost))
        val after = before - cost
        set(playerId, before, after, scheduler.currentTick())
        return Result.success(after)
    }

    override fun reset(playerId: PlayerId) {
        val before = value(playerId)
        lastGainTick.remove(playerId)
        if (before == 0) return
        set(playerId, before, 0, scheduler.currentTick())
    }

    override fun decay(nowTick: Long) {
        if (config.decayPerSecond <= 0) return
        for ((playerId, before) in values.toList()) {
            val last = lastGainTick[playerId] ?: Long.MIN_VALUE / 2
            if (nowTick - last < config.decayDelayTicks) continue
            set(playerId, before, (before - config.decayPerSecond).coerceAtLeast(0), nowTick)
        }
    }

    private fun set(playerId: PlayerId, before: Int, after: Int, tick: Long) {
        if (after == 0) values.remove(playerId) else values[playerId] = after
        if (after == before) return
        eventBus.publish(
            EgoGaugeChanged(
                playerId = playerId,
                value = after,
                max = config.max,
                becameFull = after >= config.max && before < config.max,
                atTick = tick,
            ),
        )
    }
}
