package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.event.DomainEvent
import kotlin.reflect.KClass

/** Routed in-process events. Delivery is explicit; handlers run in registration order on their owner. */
public interface IDomainEventBus {

    /** Local subscriptions see only this instance, including player routes admitted to it. */
    public fun local(id: com.astarworks.astera.core.domain.game.InstanceId): IDomainEventBus

    /** Publish to the declared route, dispatching each subscription on its declared owner. */
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
        delivery: EventDelivery,
        handler: (E) -> Unit,
    ): Subscription

    public interface Subscription {
        public fun cancel()
        public val isActive: Boolean
    }
}
