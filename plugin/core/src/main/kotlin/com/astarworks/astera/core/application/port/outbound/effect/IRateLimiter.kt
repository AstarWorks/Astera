package com.astarworks.astera.core.application.port.outbound.effect

import com.astarworks.astera.core.domain.effect.ratelimit.RateLimitedAction
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Outbound port: per-player action rate limiter
 * (Wave 5effects / ADR-0109, Wave 5q anti-cheat reinforcement).
 *
 * # Semantics
 *
 * Token-bucket style: each `(playerId, action)` pair owns its own
 * sliding window. [tryConsume] returns `true` if the call fits inside
 * the budget (atomically reserving a slot) and `false` if the budget
 * is exhausted. The window length is `windowTicks`; the cap is
 * `maxPerWindow`.
 *
 * # Where it's used
 *
 *  - Wave 5q anti-cheat: cap movement / damage / interact event volume
 *    per player.
 *  - Wave 5e skill quickcast: prevent macro spam beyond cooldown.
 *  - Wave 5_chat: chat message spam guard.
 *
 * # Implementation contract
 *
 *  - Implementations MUST be O(1) per call (in-memory uses a ring buffer
 *    of timestamps + bump-on-consume).
 *  - The default in-memory impl prunes stale slots lazily on consume;
 *    long-idle players do not accumulate state until they re-engage.
 *  - Tick clock comes from `IScheduler.currentTick()` — same source as
 *    `IParticleScheduler` and `ICountdownTimer` so all three stay in
 *    lock-step.
 */
public interface IRateLimiter {

    /**
     * Try to consume one slot for ([playerId], [action]) within a window
     * of [windowTicks] ticks holding at most [maxPerWindow] consumes.
     *
     * Returns `true` if the call fits (slot reserved); `false` if rate
     * limit reached.
     */
    public fun tryConsume(
        playerId: PlayerId,
        action: RateLimitedAction,
        maxPerWindow: Int,
        windowTicks: Long,
    ): Boolean

    /** Clear all rate-limit state for [playerId] (player disconnect / reset). */
    public fun reset(playerId: PlayerId)

    /** Clear every (player, action) entry. Test convenience. */
    public fun resetAll()
}
