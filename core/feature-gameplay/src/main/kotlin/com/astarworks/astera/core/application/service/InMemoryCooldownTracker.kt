package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.application.port.outbound.ICooldownTracker
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.cooldown.CooldownKey
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [ICooldownTracker] backed by a [ConcurrentHashMap].
 *
 * Values are absolute expiry ticks (`scheduler.currentTick() + duration`).
 * Cooldowns survive as long as the JVM — a server restart clears them, which
 * is acceptable for Phase 2 game modes that do not require persistent timers.
 *
 * [tick] is the single scan-and-evict loop: call it every game tick from
 * [com.astarworks.astera.combat.application.usecase.status.TickCooldownUseCase].
 * It returns the set of expired pairs so callers can publish events without
 * coupling the tracker to the event bus.
 */
public class InMemoryCooldownTracker(private val scheduler: IScheduler) : ICooldownTracker {

    /** Key → absolute expiry tick. */
    private val expiresAt: ConcurrentHashMap<Pair<PlayerId, CooldownKey>, Long> = ConcurrentHashMap()

    override fun isCoolingDown(playerId: PlayerId, key: CooldownKey): Boolean =
        remainingTicks(playerId, key) > 0

    override fun remainingTicks(playerId: PlayerId, key: CooldownKey): Long {
        val expires = expiresAt[Pair(playerId, key)] ?: return 0L
        return (expires - scheduler.currentTick()).coerceAtLeast(0L)
    }

    override fun start(playerId: PlayerId, key: CooldownKey, durationTicks: Long) {
        require(durationTicks >= 0L) { "durationTicks must be >= 0: $durationTicks" }
        expiresAt[Pair(playerId, key)] = scheduler.currentTick() + durationTicks
    }

    override fun reset(playerId: PlayerId, key: CooldownKey) {
        expiresAt.remove(Pair(playerId, key))
    }

    override fun tick(currentTick: Long): Set<Pair<PlayerId, CooldownKey>> {
        val expired = mutableSetOf<Pair<PlayerId, CooldownKey>>()
        val iterator = expiresAt.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= currentTick) {
                expired.add(entry.key)
                iterator.remove()
            }
        }
        return expired
    }

    override fun activeKeysFor(playerId: PlayerId): Set<CooldownKey> {
        val now = scheduler.currentTick()
        return expiresAt.entries
            .filter { (k, v) -> k.first == playerId && v > now }
            .map { it.key.second }
            .toSet()
    }
}
