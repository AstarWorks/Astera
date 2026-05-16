package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent
import kotlin.reflect.KClass

/**
 * In-memory [IDomainEventBus] for tests. Every published event is appended to
 * [published] so assertions can inspect type, count, and payload; subscribers
 * still dispatch synchronously so listeners can be exercised end-to-end.
 */
public class FakeDomainEventBus : IDomainEventBus {

    public val published: MutableList<DomainEvent> = mutableListOf()

    private data class Entry<E : DomainEvent>(
        val type: KClass<E>,
        val handler: (E) -> Unit,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry<out DomainEvent>> = mutableListOf()

    override fun publish(event: DomainEvent) {
        published += event
        for (entry in entries.toList()) {
            if (!entry.active) continue
            if (entry.type.isInstance(event)) {
                @Suppress("UNCHECKED_CAST")
                (entry.handler as (DomainEvent) -> Unit)(event)
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

    public inline fun <reified E : DomainEvent> publishedOfType(): List<E> =
        published.filterIsInstance<E>()
}
