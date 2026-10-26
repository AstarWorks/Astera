package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ProxyError
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName

/**
 * Outbound port: cross-server messaging over a Paper/Velocity proxy plugin
 * channel (ADR-0084, Wave 5r).
 *
 * Phase 1 in-memory default (`InMemoryProxyMessenger`) lets domain tests
 * publish + subscribe without any proxy. Wave 6b lands the real
 * `VelocityProxyMessenger` (Velocity-side) and `BungeeProxyMessenger`
 * (Paper-side fallback) at priority 100.
 *
 * Semantics:
 * - [sendToServer] is the high-level "move a player to another backend".
 *   Concretely on Velocity this becomes a `ConnectionRequestBuilder.connect()`
 *   via the proxy's player handle. Plain Paper (vanilla single-server) cannot
 *   service this and returns [ProxyError.NoProxyConnection].
 * - [sendMessage] / [broadcast] are raw byte-payload publishes on a logical
 *   [ProxyChannel]. Sub-protocols on top (sub-channel routing, framing,
 *   serializer choice) live above this port.
 * - [subscribe] hands back a [Subscription] the caller cancels on shutdown.
 *   Multiple subscribers on the same channel each receive every inbound
 *   message in registration order.
 *
 * No raw `Throwable` leaks: all results are [Result] with a sealed [ProxyError].
 * The InMemory impl additionally publishes [com.astarworks.astera.core.domain.event.ProxyHeartbeat]
 * whenever the registered neighbor set changes, so listeners (e.g. lobby
 * server-browser) can react without polling.
 */
public interface IProxyMessenger {

    /**
     * Relocate [playerId] to backend [targetServer] via the proxy.
     *
     * On success, the proxy will perform the actual connection swap
     * asynchronously; success here means "the proxy accepted the request",
     * not "the player has arrived". Subscribe to
     * [com.astarworks.astera.core.domain.event.PlayerArrivedOnServer] for
     * arrival confirmation.
     */
    public fun sendToServer(playerId: PlayerId, targetServer: ServerName): Result<Unit, ProxyError>

    /** Send raw [payload] to [targetServer] on the given [channel]. */
    public fun sendMessage(
        targetServer: ServerName,
        channel: ProxyChannel,
        payload: ByteArray,
    ): Result<Unit, ProxyError>

    /** Send raw [payload] to every server the proxy knows about. */
    public fun broadcast(channel: ProxyChannel, payload: ByteArray): Result<Unit, ProxyError>

    /** Subscribe to inbound messages on [channel]. */
    public fun subscribe(channel: ProxyChannel, handler: (ProxyMessage) -> Unit): Subscription

    /** Cancellable handle returned by [subscribe]. */
    public interface Subscription {
        public fun cancel()
        public val isActive: Boolean
    }
}
