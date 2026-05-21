package com.astarworks.astera.core.domain.packet

/**
 * Handle returned by [com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor.register]
 * (Wave 5y, ADR-0090).
 *
 * Symmetric to [com.astarworks.astera.core.application.port.outbound.IDomainEventBus.Subscription]
 * — callers retain the subscription to cancel during `onDisable`, and may poll
 * [isActive] for assertions.
 */
public interface InterceptorSubscription {

    public fun cancel()

    public val isActive: Boolean
}
