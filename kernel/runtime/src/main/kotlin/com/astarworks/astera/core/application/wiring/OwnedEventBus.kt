package com.astarworks.astera.core.application.wiring

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.event.DomainEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The event bus as one wiring generation sees it (ADR-0311): delegates to [inner] and records every
 * subscription under [tag], so [cancelAll] removes exactly what this generation subscribed.
 */
public class OwnedEventBus(private val inner: IDomainEventBus, public val tag: WiringTag) : IDomainEventBus {

    private val live: MutableSet<IDomainEventBus.Subscription> = ConcurrentHashMap.newKeySet()

    /** Subscriptions of this generation that are still active. */
    public val activeCount: Int get() = live.count { it.isActive }

    override fun publish(event: DomainEvent) {
        inner.publish(event)
    }

    override fun <E : DomainEvent> subscribe(type: KClass<E>, handler: (E) -> Unit): IDomainEventBus.Subscription {
        val raw = inner.subscribe(type, handler)
        val tracked = object : IDomainEventBus.Subscription {
            override fun cancel() {
                raw.cancel()
                live.remove(this)
            }
            override val isActive: Boolean get() = raw.isActive
        }
        live += tracked
        return tracked
    }

    /** Cancels every subscription this generation still holds. */
    public fun cancelAll() {
        live.toList().forEach { it.cancel() }
    }
}
