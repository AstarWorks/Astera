package com.astarworks.astera.core.domain.proxy

import com.astarworks.astera.core.domain.match.MatchId

/**
 * When a queued transfer should fire (ADR-0084, Wave 5r).
 *
 * Used by [com.astarworks.astera.core.application.port.outbound.IServerTransferGateway.queueTransfer]
 * to defer a move that cannot happen synchronously. The Phase 1 in-memory
 * gateway materializes the schedule itself; Wave 6b's Velocity adapter will
 * delegate to the proxy's own scheduler.
 *
 * The `when` keyword in Kotlin precludes naming the parameter `when` on the
 * call site, hence [com.astarworks.astera.core.application.port.outbound.IServerTransferGateway]
 * names it `when_`. The type itself does not need the trailing underscore.
 */
public sealed interface TransferWhen {

    /**
     * Fire as soon as the gateway is asked to flush
     * (`InMemoryServerTransferGateway.flushImmediate()` for tests; the live
     * adapter flushes after this tick).
     */
    public data object Immediately : TransferWhen

    /**
     * Fire the next time the player logs in. Useful when the target backend
     * is currently full / starting / under maintenance and you want the
     * player to land on it the moment they reconnect.
     *
     * @property timeoutSeconds The queued transfer is dropped if the player
     *   does not reconnect within this many seconds (default 5 min). 0 means
     *   "never expire", which the adapter is free to refuse.
     */
    public data class OnNextLogin(val timeoutSeconds: Int = 300) : TransferWhen {
        init {
            require(timeoutSeconds >= 0) { "timeoutSeconds must be non-negative: $timeoutSeconds" }
        }
    }

    /**
     * Fire the moment match [matchId] ends. The gateway listens for
     * `MatchEnded` and dispatches the queued moves for matching players.
     */
    public data class AfterCurrentMatch(val matchId: MatchId) : TransferWhen
}
