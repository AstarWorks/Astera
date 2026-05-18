package com.astarworks.astera.combat.application.service

import com.astarworks.astera.combat.application.port.outbound.IDamageHistoryService
import com.astarworks.astera.combat.domain.damage.DamageEntry
import com.astarworks.astera.combat.domain.damage.DamageHistoryWindow
import com.astarworks.astera.core.domain.player.PlayerId
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory ring-buffered [IDamageHistoryService]. ADR-0063.
 *
 * Per-target [ArrayDeque] sized to [CAP_PER_TARGET] (oldest entry pops on
 * overflow). Acceptable for assist credit because any single attacker from
 * the live tail is enough to credit; a burst > 64 hits in 5 seconds losing
 * the very oldest entries is harmless.
 *
 * Cleanup is the caller's responsibility — `MatchEnded` and player
 * disconnect should invoke [clear] (wired in Wave 5e/6 lifecycle listeners).
 */
public class InMemoryDamageHistoryService(
    private val capPerTarget: Int = CAP_PER_TARGET,
) : IDamageHistoryService {

    private val byTarget = ConcurrentHashMap<PlayerId, ArrayDeque<DamageEntry>>()

    override fun record(targetId: PlayerId, entry: DamageEntry) {
        val deque = byTarget.computeIfAbsent(targetId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(entry)
            while (deque.size > capPerTarget) {
                deque.removeFirst()
            }
        }
    }

    override fun recentAttackers(
        targetId: PlayerId,
        window: DamageHistoryWindow,
        asOf: Long,
    ): List<PlayerId> {
        val deque = byTarget[targetId] ?: return emptyList()
        val threshold = asOf - window.durationTicks
        val snapshot = synchronized(deque) { deque.toList() }
        return snapshot.asSequence()
            .filter { it.at >= threshold }
            .map { it.attackerId }
            .distinct()
            .toList()
    }

    override fun clear(targetId: PlayerId) {
        byTarget.remove(targetId)
    }

    override fun entries(targetId: PlayerId): List<DamageEntry> {
        val deque = byTarget[targetId] ?: return emptyList()
        return synchronized(deque) { deque.toList() }
    }

    public companion object {
        public const val CAP_PER_TARGET: Int = 64
    }
}
