package com.astarworks.astera.testfixtures.effect

import com.astarworks.astera.core.application.port.outbound.effect.IRateLimiter
import com.astarworks.astera.core.domain.effect.ratelimit.RateLimitedAction
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Configurable [IRateLimiter] for tests (Wave 5effects / ADR-0109).
 *
 * `acceptAll = true` (default) lets every `tryConsume` through and
 * records the call. Toggle to `false` to model "rate-limit triggered"
 * scenarios. [calls] preserves call order.
 */
public class FakeRateLimiter(public var acceptAll: Boolean = true) : IRateLimiter {

    public data class Call(
        public val playerId: PlayerId,
        public val action: RateLimitedAction,
        public val maxPerWindow: Int,
        public val windowTicks: Long,
        public val accepted: Boolean,
    )

    public val calls: MutableList<Call> = mutableListOf()
    public val resets: MutableList<PlayerId> = mutableListOf()

    override fun tryConsume(
        playerId: PlayerId,
        action: RateLimitedAction,
        maxPerWindow: Int,
        windowTicks: Long,
    ): Boolean {
        val accepted = acceptAll
        calls += Call(playerId, action, maxPerWindow, windowTicks, accepted)
        return accepted
    }

    override fun reset(playerId: PlayerId) {
        resets += playerId
    }

    override fun resetAll() {
        calls.clear()
        resets.clear()
    }
}
