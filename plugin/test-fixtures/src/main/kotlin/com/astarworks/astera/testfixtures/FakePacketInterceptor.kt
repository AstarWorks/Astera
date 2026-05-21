package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind

/**
 * Cross-plugin Fake for [IPacketInterceptor] (Wave 5y, ADR-0090).
 *
 * Records every registration so tests can assert "did Wave 5q's bridge
 * subscribe to `MovementServerbound`?" without spinning up the Wave 6b
 * PacketEvents adapter. The [dispatch] helper mirrors the in-memory variant
 * — short-circuit on Cancel, threads Replace through the listener chain.
 */
public class FakePacketInterceptor : IPacketInterceptor {

    public data class Registration(
        public val kind: PacketKind,
        public val listener: (PacketEnvelope) -> PacketDecision,
    )

    private data class Entry(
        val registration: Registration,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry> = mutableListOf()

    /** Defensive snapshot of currently active registrations. */
    public val registrations: List<Registration>
        get() = entries.filter { it.active }.map { it.registration }

    override fun register(
        kind: PacketKind,
        listener: (PacketEnvelope) -> PacketDecision,
    ): InterceptorSubscription {
        val entry = Entry(Registration(kind, listener))
        entries += entry
        return object : InterceptorSubscription {
            override fun cancel() {
                entry.active = false
                entries.remove(entry)
            }
            override val isActive: Boolean get() = entry.active
        }
    }

    override fun unregister(subscription: InterceptorSubscription) {
        subscription.cancel()
    }

    public fun dispatch(envelope: PacketEnvelope): PacketDecision {
        var currentEnv = envelope
        var lastDecision: PacketDecision = PacketDecision.Pass
        for (entry in entries.toList()) {
            if (!entry.active) continue
            if (entry.registration.kind != currentEnv.kind) continue
            when (val decision = entry.registration.listener(currentEnv)) {
                is PacketDecision.Cancel -> return PacketDecision.Cancel
                is PacketDecision.Replace -> {
                    currentEnv = currentEnv.copy(payloadView = currentEnv.payloadView + decision.newPayload)
                    lastDecision = decision
                }
                is PacketDecision.Pass -> {}
            }
        }
        return lastDecision
    }

    public fun clear() {
        entries.clear()
    }
}
