package com.astarworks.astera.application.port.outbound

import com.astarworks.astera.domain.event.DomainEvent

/**
 * Outbound port: publish a domain event.
 *
 * Phase 1: implementations may simply log. Phase 2+ will fan out to
 * persistence, Redis pub/sub, Discord notifier, etc., still via this port.
 */
interface IBroadcaster {
    fun publish(event: DomainEvent)
}
