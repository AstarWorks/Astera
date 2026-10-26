package com.astarworks.astera.core.application.eventbus

import com.astarworks.astera.core.domain.event.DomainEvent
import kotlin.reflect.KClass

/**
 * Application-layer listener for a specific [DomainEvent] variant.
 *
 * Implementations delegate to use cases — they should not contain business
 * logic themselves. Stage 2 listeners live in
 * `application/eventbus/listeners/`; the wiring layer (`AsteraPlugin`)
 * registers them via [DomainEventListenerRegistry].
 */
public interface DomainEventListener<E : DomainEvent> {
    public val eventType: KClass<E>
    public fun handle(event: E)
}
