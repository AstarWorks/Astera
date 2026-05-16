package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.event.DomainEvent
import kotlin.reflect.KClass

/**
 * Outbound port: in-process domain event bus with publish + subscribe.
 *
 * Coexists with [IBroadcaster]: that port keeps a single, fire-and-forget
 * publish semantics for adapters (logging, Discord, Redis fan-out, etc.).
 * This port adds **subscribe** so application-layer listeners can react in
 * the same process without going through an external broker.
 *
 * silly-knuth §5: type dispatch happens via [KClass.isInstance] only — no
 * reflection-based discovery; subscribers register themselves explicitly.
 *
 * Phase 2: a single in-memory implementation is wired. Phase 6 may add a
 * Redis-backed impl; subscribers stay framework-agnostic.
 */
public interface IDomainEventBus {

    /** Publish [event] to all matching subscribers synchronously. */
    public fun publish(event: DomainEvent)

    /**
     * Subscribe to events whose runtime type is assignable to [type]. Returns
     * a [Subscription] the caller cancels when no longer interested.
     *
     * Multiple subscriptions on the same type all receive the event in
     * registration order.
     */
    public fun <E : DomainEvent> subscribe(
        type: KClass<E>,
        handler: (E) -> Unit,
    ): Subscription

    public interface Subscription {
        public fun cancel()
        public val isActive: Boolean
    }
}
