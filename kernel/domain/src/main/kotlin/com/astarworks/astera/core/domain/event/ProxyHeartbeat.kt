package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.proxy.ServerName
import java.time.Instant

/**
 * Published periodically by the proxy adapter to report the set of currently
 * online peer servers (ADR-0084, Wave 5r).
 *
 * Use cases:
 *  - server-browser HUD: list only [knownPeers] that are alive
 *  - matchmaker: drop matches that span a peer no longer in the heartbeat
 *  - admin tooling: surface a peer-down warning
 *
 * The InMemory messenger publishes this whenever
 * `InMemoryProxyMessenger.addNeighbor` is called, so domain tests can drive
 * subscriber behavior without a live proxy. The Velocity adapter publishes
 * on a fixed interval (default 30s) using the proxy's `ProxyServer.allServers`.
 */
public data class ProxyHeartbeat(
    val localServer: ServerName,
    val knownPeers: Set<ServerName>,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
