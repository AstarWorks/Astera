package com.astarworks.astera.core.application.eventbus.listeners.proxy

import com.astarworks.astera.core.domain.event.PlayerTransferRequested
import org.slf4j.LoggerFactory

/**
 * Phase 1 PoC stub subscriber for [PlayerTransferRequested] (ADR-0132 §B).
 *
 * Closes the silent-drop pattern: the event is published by
 * [com.astarworks.astera.core.application.service.InMemoryServerTransferGateway.transfer]
 * before the proxy is asked to move the player, but no subscriber existed,
 * meaning the "move is about to happen" notification was a dead letter.
 * This listener logs the request at INFO; richer subscribers (lobby HUD
 * "you are being moved to…", admin audit log, inventory snapshot save)
 * land in later waves (Wave 5e / _persist-1).
 *
 * Stub-only by design: Phase 1 has no live multi-server setup. Wave 5r /
 * ADR-0084 established the event contract; this listener completes the
 * Phase 1 wiring contract surfaced by audit α#4.
 */
public class LogOnlyPlayerTransferRequestedListener {

    private val log = LoggerFactory.getLogger(LogOnlyPlayerTransferRequestedListener::class.java)

    public fun handle(event: PlayerTransferRequested) {
        log.info(
            "Transfer requested: player={} target={} reason={}",
            event.playerId,
            event.targetServer,
            event.transferReason,
        )
    }
}
