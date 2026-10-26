package com.astarworks.astera.core.domain.event.lifecycle

import com.astarworks.astera.core.domain.event.EventRoute

import com.astarworks.astera.core.domain.event.DomainEvent
import java.time.Instant

/**
 * Published when [com.astarworks.astera.core.application.port.outbound.lifecycle.IServerBootstrapper.markReady]
 * finishes (Wave 5w / ADR-0089). The server now accepts player joins;
 * downstream HUD coordinators may begin polling matchmaker state.
 */
public data class ServerReady(
    val readyAt: Instant,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent {
    override val route: EventRoute get() = EventRoute.Global
}
