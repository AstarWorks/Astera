package com.astarworks.astera.core.application.service.packet

import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind

/**
 * In-memory [IPacketInterceptor] (Wave 5y, ADR-0090).
 *
 * Plays the role of the Wave 6b PacketEvents-backed adapter for tests, scenario
 * DSL (ADR-0059), and the vanilla-Paper fallback when PacketEvents is not
 * loaded. The [dispatch] method is exposed so tests + the anti-cheat-bridge
 * unit test can synthesise a packet observation without booting Netty.
 *
 * Listener semantics match [IPacketInterceptor]:
 *  - registration order is preserved,
 *  - first [PacketDecision.Cancel] short-circuits the chain,
 *  - [PacketDecision.Replace] feeds the next listener a new envelope with
 *    the merged payload (existing keys overwritten, others left untouched).
 *
 * NOT thread-safe — like the Paper-backed equivalent, listeners must run on
 * a single dispatcher thread. The test fixtures call [dispatch] from JUnit's
 * main thread.
 */
public class InMemoryPacketInterceptor : IPacketInterceptor {

    private data class Entry(
        val kind: PacketKind,
        val listener: (PacketEnvelope) -> PacketDecision,
        var active: Boolean = true,
    )

    private val entries: MutableList<Entry> = mutableListOf()

    override fun register(
        kind: PacketKind,
        listener: (PacketEnvelope) -> PacketDecision,
    ): InterceptorSubscription {
        val entry = Entry(kind, listener)
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

    /**
     * Dispatch [envelope] through every active listener registered for its
     * [PacketEnvelope.kind] in registration order. Returns the final decision
     * — [PacketDecision.Cancel] if any listener cancelled, otherwise the last
     * [PacketDecision.Replace] (or [PacketDecision.Pass]).
     *
     * Called by:
     *  - the Wave 6b adapter's PacketEvents `onPacketReceive` / `onPacketSend`
     *    once it converts a `PacketWrapper` to a [PacketEnvelope];
     *  - unit tests + the `MovementValidatorPacketBridge` integration test
     *    that asserts a `Reject` validation cancels the inbound move packet.
     */
    public fun dispatch(envelope: PacketEnvelope): PacketDecision {
        var currentEnv = envelope
        var lastDecision: PacketDecision = PacketDecision.Pass
        for (entry in entries.toList()) {
            if (!entry.active) continue
            if (entry.kind != currentEnv.kind) continue
            val decision = entry.listener(currentEnv)
            when (decision) {
                is PacketDecision.Cancel -> return PacketDecision.Cancel
                is PacketDecision.Replace -> {
                    currentEnv = currentEnv.copy(payloadView = currentEnv.payloadView + decision.newPayload)
                    lastDecision = decision
                }
                is PacketDecision.Pass -> {
                    // Keep prior Replace if present, otherwise Pass.
                }
            }
        }
        return lastDecision
    }

    /** Test-only: number of active listeners for [kind]. */
    public fun listenerCount(kind: PacketKind): Int =
        entries.count { it.active && it.kind == kind }
}
