package com.astarworks.astera.core.domain.event.lifecycle

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.lifecycle.ShutdownReason
import java.time.Instant

/**
 * Published the moment [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper.shutdown]
 * is called, before any phase runs (Wave 5w / ADR-0089).
 *
 * Match plugin subscribes to drive lobby HUDs into "Server shutting down…"
 * state; reward bridge subscribes to start a "no new outbox entries" gate.
 */
public data class ServerShutdownStarted(
    val reason: ShutdownReason,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
