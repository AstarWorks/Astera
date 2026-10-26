package com.astarworks.astera.core.application.port.outbound.world

import com.astarworks.astera.core.domain.event.DomainEvent
import com.astarworks.astera.core.domain.region.RegionId
import kotlin.reflect.KClass

/**
 * Outbound port: subscribe to domain events filtered by **region of origin**
 * (Wave 5world-events, ADR-0111).
 *
 * Complements [com.astarworks.astera.core.application.port.outbound.IDomainEventBus]:
 * the event bus is type-routed, this port adds a **spatial** filter so a
 * region-permission rule (Wave 5n) can subscribe to "every interact event
 * inside the no-build region" without re-implementing the region-membership
 * check in every subscriber.
 *
 * Implementations are expected to consult
 * [com.astarworks.astera.core.application.port.outbound.IRegionPermissionService]
 * (or the future spatial index) to resolve "did this event happen inside
 * [regionId]?" — events that lack a position (e.g. [WeatherChanged]) are
 * never delivered to a region-filtered subscriber.
 *
 * Returns a [Subscription] the caller cancels when no longer interested,
 * matching the [IDomainEventBus] convention.
 */
public interface IRegionEventFilter {

    /**
     * Subscribe to events of type [type] that originate inside [regionId].
     *
     * Multiple subscriptions on the same (region, type) pair all receive
     * each matching event in registration order.
     */
    public fun <E : DomainEvent> subscribe(
        regionId: RegionId,
        type: KClass<E>,
        delivery: com.astarworks.astera.core.application.port.outbound.EventDelivery,
        handler: (E) -> Unit,
    ): Subscription

    public interface Subscription {
        public fun cancel()
        public val isActive: Boolean
    }
}
