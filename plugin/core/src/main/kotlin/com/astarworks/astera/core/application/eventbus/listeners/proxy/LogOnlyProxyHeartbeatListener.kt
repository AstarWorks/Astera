package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.ProxyHeartbeat
import org.slf4j.LoggerFactory

/**
 * Phase 1 PoC stub subscriber for [ProxyHeartbeat] (ADR-0132 §C).
 *
 * Closes the silent-drop pattern: the event would be published periodically
 * by the Wave 6b Velocity adapter (and on-demand by
 * `InMemoryProxyMessenger.addNeighbor`), but no subscriber existed, so
 * heartbeat traffic was a dead letter. This listener logs at DEBUG so the
 * heartbeat is observable but does not flood INFO; richer subscribers
 * (server-browser HUD updates, matchmaker peer-down filtering, admin
 * peer-down warnings) land in later waves.
 *
 * Stub-only by design: Phase 1 has no live multi-server setup. Wave 5r /
 * ADR-0084 established the event contract; this listener completes the
 * Phase 1 wiring contract surfaced by audit α#4.
 */
public class LogOnlyProxyHeartbeatListener {

    private val log = LoggerFactory.getLogger(LogOnlyProxyHeartbeatListener::class.java)

    public fun handle(event: ProxyHeartbeat) {
        log.debug(
            "Proxy heartbeat: local={} peers={}",
            event.localServer,
            event.knownPeers,
        )
    }
}
