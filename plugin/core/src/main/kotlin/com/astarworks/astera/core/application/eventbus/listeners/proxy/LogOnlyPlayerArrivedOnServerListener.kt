package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.PlayerArrivedOnServer
import org.slf4j.LoggerFactory

/**
 * Phase 1 PoC stub subscriber for [PlayerArrivedOnServer] (ADR-0132 §A).
 *
 * Closes the silent-drop pattern: the event has been published by
 * `VelocityProxyMessenger` (Wave 6b) and by tests, but no subscriber existed,
 * meaning any real arrival was a dead letter. This listener logs the arrival
 * at INFO so operators can see proxy traffic in the server log; richer
 * subscribers (inventory restore on arrival, party state sync, lobby UI
 * refresh) land in later waves once the persistence-side handoff is wired.
 *
 * Stub-only by design: Phase 1 has no live multi-server setup. Wave 5r /
 * ADR-0084 established the event contract; this listener completes the
 * Phase 1 wiring contract that Konsist Rule 54 (ADR-0131) and audit α#4
 * surfaced as a gap.
 */
public class LogOnlyPlayerArrivedOnServerListener {

    private val log = LoggerFactory.getLogger(LogOnlyPlayerArrivedOnServerListener::class.java)

    public fun handle(event: PlayerArrivedOnServer) {
        log.info(
            "Player {} arrived on {} from {}",
            event.playerId,
            event.targetServer,
            event.fromServer,
        )
    }
}
