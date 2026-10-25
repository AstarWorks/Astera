package com.astarworks.astera.core.application.service.effect

import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.effect.IRateLimiter
import com.astarworks.astera.core.domain.effect.ratelimit.RateLimitedAction
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Default in-memory [IRateLimiter]. Wave 5effects (ADR-0109).
 *
 * Per-`(playerId, action)` ring of timestamps. `tryConsume`:
 *
 *  1. Reads `now = scheduler.currentTick()`.
 *  2. Prunes entries older than `now - windowTicks`.
 *  3. If `count >= maxPerWindow` → returns `false` (rate-limited).
 *  4. Otherwise appends `now` and returns `true`.
 *
 * O(1) amortised — prune walks at most `maxPerWindow` entries, and the
 * deque length stays ≤ `maxPerWindow` after the early-bail check.
 *
 * Not thread-safe (Paper main thread invariant). Tests use the same
 * impl via test-fixtures' deterministic [com.astarworks.astera.testfixtures.FakeScheduler].
 */
public class InMemoryRateLimiter(
    private val scheduler: IScheduler,
) : IRateLimiter {

    private val state: MutableMap<Key, ArrayDeque<Long>> = mutableMapOf()

    override fun tryConsume(
        playerId: PlayerId,
        action: RateLimitedAction,
        maxPerWindow: Int,
        windowTicks: Long,
    ): Boolean {
        require(maxPerWindow >= 1) { "maxPerWindow must be >= 1" }
        require(windowTicks >= 1) { "windowTicks must be >= 1" }
        val now = scheduler.currentTick()
        val cutoff = now - windowTicks + 1L
        val bucket = state.getOrPut(Key(playerId, action)) { ArrayDeque() }
        while (bucket.isNotEmpty() && bucket.first() < cutoff) bucket.removeFirst()
        if (bucket.size >= maxPerWindow) return false
        bucket.addLast(now)
        return true
    }

    override fun reset(playerId: PlayerId) {
        state.keys.removeAll { it.playerId == playerId }
    }

    override fun resetAll() {
        state.clear()
    }

    private data class Key(val playerId: PlayerId, val action: RateLimitedAction)
}
