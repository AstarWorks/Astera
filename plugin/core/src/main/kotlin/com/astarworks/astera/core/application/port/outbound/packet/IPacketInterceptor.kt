package com.astarworks.astera.core.application.port.outbound.packet

import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind

/**
 * Outbound port — server-side packet interception with curated surface
 * (Wave 5y, ADR-0090).
 *
 * The Wave 6b adapter is implemented on top of
 * [PacketEvents](https://github.com/retrooper/packetevents) (soft-dep,
 * ADR-0009 / ADR-0015 supersede). When PacketEvents is absent the adapter
 * registers a no-op interceptor that swallows every [register] call without
 * crashing (returning an inert subscription); call sites observe the
 * degraded behaviour via the existing
 * [com.astarworks.astera.core.domain.visibility.PacketBackendError.PacketEventsUnavailable]
 * surface raised by the per-visibility gateway, not by this port.
 *
 * **Ordering.** Listeners fire in registration order for a given [PacketKind];
 * the first [PacketDecision.Cancel] short-circuits the chain (subsequent
 * listeners are NOT invoked, mirroring Bukkit's `EventPriority` shape).
 * [PacketDecision.Replace] composes — the next listener sees the replaced
 * payload in the envelope view.
 *
 * **Thread-safety.** Listeners are invoked from PacketEvents' Netty thread.
 * Production listeners MUST be allocation-free + side-effect-free on that
 * path; cross-plugin side effects belong on `IScheduler.runOnMain` or via
 * `IDomainEventBus` (queued).
 */
public interface IPacketInterceptor {

    /**
     * Register [listener] for [kind]. The returned [InterceptorSubscription]
     * is the only way to remove it — listeners are NOT keyed by identity
     * (a single function reference may be registered multiple times).
     */
    public fun register(
        kind: PacketKind,
        listener: (PacketEnvelope) -> PacketDecision,
    ): InterceptorSubscription

    /** Cancel a previously-registered listener. Idempotent. */
    public fun unregister(subscription: InterceptorSubscription)
}
