package com.astarworks.astera.core.domain.event

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.proxy.ServerName
import java.time.Instant

/**
 * The target backend confirmed [playerId] arrived from another Astera node
 * (ADR-0084, Wave 5r).
 *
 * Published by Wave 6b's `VelocityProxyMessenger` after it receives the
 * arrival message on the `astera.transfer` channel. In Phase 1 in-memory
 * topology, [com.astarworks.astera.core.application.service.InMemoryProxyMessenger]
 * does not synthesize this event — tests publish it explicitly via the
 * event bus, mirroring what the live adapter will do.
 *
 * Distinct from [PlayerJoinedServer] which fires on **every** join including
 * fresh logins; this fires **only** on proxy-mediated arrivals.
 */
public data class PlayerArrivedOnServer(
    val playerId: PlayerId,
    val fromServer: ServerName,
    val targetServer: ServerName,
    override val occurredAt: Instant = Instant.now(),
    override val version: Int = 1,
) : DomainEvent
