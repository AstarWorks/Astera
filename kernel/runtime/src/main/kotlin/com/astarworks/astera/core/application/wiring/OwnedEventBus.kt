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

    @Volatile private var held = false
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Held>()

    /**
     * Staging mode (ADR-0311 P2): a subscription made while a generation is only wired, not bound, waits and joins
     * the bus on [release], so the old and the new generation never both see an event during the swap.
     */
    public fun hold() {
        held = true
    }

    /** Attaches every held subscription and leaves staging mode. */
    public fun release() {
        held = false
        while (true) pending.poll()?.attach() ?: break
    }

    private inner class Held(private val attachTo: () -> IDomainEventBus.Subscription) : IDomainEventBus.Subscription {
        @Volatile private var raw: IDomainEventBus.Subscription? = null
        @Volatile private var cancelled = false

        @Synchronized
        fun attach() {
            if (!cancelled) raw = attachTo()
        }

        @Synchronized
        override fun cancel() {
            cancelled = true
            raw?.cancel()
            live.remove(this)
        }

        override val isActive: Boolean get() = !cancelled && (raw?.isActive ?: held)
    }

    override fun <E : DomainEvent> subscribe(type: KClass<E>, handler: (E) -> Unit): IDomainEventBus.Subscription {
        val tracked = Held { inner.subscribe(type, handler) }
        live += tracked
        if (held) pending += tracked else tracked.attach()
        return tracked
    }

    /** Cancels every subscription this generation still holds. */
    public fun cancelAll() {
        live.toList().forEach { it.cancel() }
        pending.clear()
    }
}
