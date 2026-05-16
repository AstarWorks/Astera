package com.astarworks.astera.testfixtures

import com.astarworks.astera.application.port.outbound.IBroadcaster
import com.astarworks.astera.domain.event.DomainEvent

/**
 * In-memory [IBroadcaster]. Every published event is appended to [published]
 * so tests can assert on type, count, and payload.
 */
class FakeBroadcaster : IBroadcaster {
    val published: MutableList<DomainEvent> = mutableListOf()

    override fun publish(event: DomainEvent) {
        published += event
    }
}
