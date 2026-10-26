package com.astarworks.astera.core.application.service

import java.util.concurrent.ConcurrentHashMap

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IResourcePoolService
import com.astarworks.astera.core.application.port.outbound.IResourceRegenScheduler
import com.astarworks.astera.core.domain.event.ResourceRegenned
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.resource.ResourceType
import kotlin.math.floor

/**
 * Default [IResourceRegenScheduler] (Wave 5k, ADR-0080).
 *
 * # Tick math
 *
 * Each [com.astarworks.astera.core.domain.resource.ResourceType] has a
 * regen rate in HP-equivalent units per second. At 20 TPS that's
 * `regen / 20.0` units per tick. The remainder accumulates in a
 * per-(player × type) Double until it crosses 1.0; the integer portion is
 * flushed through [IResourcePoolService.applyRegen] and the fractional
 * remainder carries forward.
 *
 * Why accumulate instead of always rounding: regen rates below 1.0 / 20 = 20
 * units per second would simply never tick if we rounded each tick
 * independently. The accumulator handles arbitrarily small rates correctly.
 *
 * # Players considered
 *
 * Every initialised player is eligible (the scheduler is given a snapshot of
 * the player set via [trackPlayer] / [untrackPlayer], driven by the join /
 * quit listener pair in Core's wiring).
 */
public class DefaultResourceRegenScheduler(
    private val resourcePool: IResourcePoolService,
    private val eventBus: IDomainEventBus,
) : IResourceRegenScheduler {

    private val tracked: MutableSet<PlayerId> = ConcurrentHashMap.newKeySet()
    private val accumulator: MutableMap<Pair<PlayerId, ResourceType>, Double> = ConcurrentHashMap()

    /** Begin regen ticks for [playerId]. Idempotent. */
    public fun trackPlayer(playerId: PlayerId) {
        tracked += playerId
    }

    /** Stop regen ticks for [playerId]. Clears the accumulator. */
    public fun untrackPlayer(playerId: PlayerId) {
        tracked -= playerId
        ResourceType.all.forEach { type ->
            accumulator.remove(playerId to type)
        }
    }

    /** Currently-tracked player set (read-only view). */
    public fun trackedPlayers(): Set<PlayerId> = tracked.toSet()

    override fun tick(currentTick: Long): Set<PlayerId> {
        if (tracked.isEmpty()) return emptySet()
        val changed = mutableSetOf<PlayerId>()
        for (playerId in tracked.toList()) if (tickPlayer(playerId, currentTick)) changed += playerId
        return changed
    }

    public fun tickPlayer(playerId: PlayerId, currentTick: Long): Boolean {
        var changed = false
        for (type in ResourceType.all) {
            val rate = resourcePool.capFor(playerId, type).regenPerSecond
            if (rate <= 0.0) continue
            val key = playerId to type
            val perTick = rate / 20.0
            val acc = (accumulator[key] ?: 0.0) + perTick
            val flush = floor(acc).toInt()
            if (flush > 0) {
                val outcome = resourcePool.applyRegen(playerId, type, flush)
                outcome.onSuccess { newValue ->
                    accumulator[key] = acc - flush
                    eventBus.publish(
                        ResourceRegenned(
                            playerId = playerId,
                            type = type,
                            amount = flush,
                            newValue = newValue,
                            atTick = currentTick,
                        ),
                    )
                    changed = true
                }.onFailure {
                    // Uninitialised player — drop accumulator; harmless.
                    accumulator.remove(key)
                }
            } else {
                accumulator[key] = acc
            }
        }
        return changed
    }
}
