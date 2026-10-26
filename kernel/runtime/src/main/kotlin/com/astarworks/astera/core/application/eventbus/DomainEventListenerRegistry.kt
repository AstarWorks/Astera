package com.astarworks.astera.core.application.eventbus

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent

/**
 * Holds the lifecycle of [DomainEventListener] subscriptions on a single bus.
 *
 * Call [registerAll] once at startup with every listener; call [cancelAll] on
 * shutdown. Idempotent within a single registry: calling [registerAll] twice
 * will create duplicate subscriptions, so don't.
 */
public class DomainEventListenerRegistry(private val bus: IDomainEventBus) {

    private val subscriptions: MutableList<IDomainEventBus.Subscription> = mutableListOf()

    public fun registerAll(listeners: Iterable<DomainEventListener<*>>) {
        for (listener in listeners) {
            subscriptions += subscribe(listener)
        }
    }

    public fun cancelAll() {
        for (s in subscriptions) s.cancel()
        subscriptions.clear()
    }

    public val activeCount: Int get() = subscriptions.count { it.isActive }

    private fun <E : DomainEvent> subscribe(listener: DomainEventListener<E>): IDomainEventBus.Subscription =
        bus.subscribe(listener.eventType, listener.delivery) { event -> listener.handle(event) }
}
