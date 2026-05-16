package com.astarworks.astera.adapter.paper.event

import com.astarworks.astera.application.port.outbound.IBroadcaster
import com.astarworks.astera.domain.event.DomainEvent
import org.slf4j.LoggerFactory

/**
 * Phase 1 [IBroadcaster] that just logs events.
 *
 * Phase 2+ replaces this with a real fan-out (Redis pub/sub, persistence,
 * Discord notifier, in-process listeners), but the [IBroadcaster] interface
 * itself stays — only the Koin binding in `platform-paper-plugin` changes.
 */
class PaperBroadcaster : IBroadcaster {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: DomainEvent) {
        log.info("DomainEvent: {}", event)
    }
}
