package com.astarworks.astera.core.application.eventbus

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

/**
 * Default [IDomainEventBus] implementation backed by a plain [MutableList].
 *
 * Not thread-safe by design: Astera dispatches on the Paper main thread, so
 * `CopyOnWriteArrayList` would be overkill. Publish takes a snapshot before
 * dispatching so a handler can subscribe / cancel during dispatch without
 * `ConcurrentModificationException`. A handler that throws is logged at ERROR and does not stop the others.
 */
public class InMemoryDomainEventBus : IDomainEventBus {

    private val log = LoggerFactory.getLogger(javaClass)

    private data class Entry<E : DomainEvent>(
        val type: KClass<E>,
        val handler: (E) -> Unit,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry<out DomainEvent>> = mutableListOf()

    override fun publish(event: DomainEvent) {
        // snapshot to allow handlers to subscribe/cancel during dispatch
        val snapshot = entries.toList()
        for (entry in snapshot) {
            if (!entry.active) continue
            if (entry.type.isInstance(event)) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (entry.handler as (DomainEvent) -> Unit)(event)
                } catch (t: Throwable) {
                    // One failing handler must never skip the handlers after it (a thrown MatchEnded handler once
                    // swallowed the result screen and the rewards): log loudly and carry on.
                    if (t is VirtualMachineError) throw t
                    log.error("Event handler failed for {}; the remaining handlers still run", event::class.qualifiedName, t)
                }
            }
        }
    }

    override fun <E : DomainEvent> subscribe(
        type: KClass<E>,
        handler: (E) -> Unit,
    ): IDomainEventBus.Subscription {
        val entry = Entry(type, handler)
        entries += entry
        return object : IDomainEventBus.Subscription {
            override fun cancel() {
                entry.active = false
                entries.remove(entry)
            }
            override val isActive: Boolean get() = entry.active
        }
    }
}
