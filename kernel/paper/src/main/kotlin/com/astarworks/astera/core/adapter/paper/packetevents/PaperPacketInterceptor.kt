package com.astarworks.astera.core.adapter.paper.packetevents

import com.astarworks.astera.core.application.port.outbound.packet.IPacketInterceptor
import com.astarworks.astera.core.domain.packet.InterceptorSubscription
import com.astarworks.astera.core.domain.packet.PacketDecision
import com.astarworks.astera.core.domain.packet.PacketEnvelope
import com.astarworks.astera.core.domain.packet.PacketKind
import com.astarworks.astera.core.domain.player.PlayerId
import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListener
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.event.ProtocolPacketEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Paper-side [IPacketInterceptor] backed by PacketEvents (ADR-0170 Wave 6c-
 * stub-packetevents).
 *
 * Replaces the [com.astarworks.astera.core.application.service.packet.InMemoryPacketInterceptor]
 * default at priority 100 when the PacketEvents plugin is installed on the
 * target server. When PacketEvents is absent the InMemory variant continues
 * to serve and surfaces packet observations only when explicitly dispatched
 * from tests / scenario DSL (per the existing Phase 1 PoC posture).
 *
 * ## Translation layer
 *
 * Astera's [PacketKind] enumeration is six symbolic categories
 * (MovementClientbound / MovementServerbound / DamageAnimation /
 * ChatClientbound / EntitySpawn / EntityDestroy). Each maps to one or more
 * PacketEvents `PacketType.Play.*` constants. The mapping is intentionally
 * narrow — most PacketEvents types stay outside Astera's interest surface
 * per ADR-0095's "limited PacketKind surface" clause; the adapter returns
 * `null` for unmapped PacketEvents events so no [PacketEnvelope] is
 * dispatched (zero overhead for the 95% of packets Astera doesn't watch).
 *
 * ### Phase 1 PoC mapping
 *
 * **Active mappings (have at least one Astera consumer)**:
 *  - `MovementServerbound` ← `PLAYER_POSITION`, `PLAYER_ROTATION`,
 *    `PLAYER_POSITION_AND_ROTATION` (Wave 5q anti-cheat
 *    `MovementValidatorPacketBridge`).
 *
 * **Reserved (mapping documented; no current consumer)**:
 *  - `MovementClientbound`, `DamageAnimation`, `ChatClientbound`,
 *    `EntitySpawn`, `EntityDestroy` — future Phase 1.x or Phase 3 waves
 *    add their listeners. The Paper adapter returns `null` from
 *    [outboundKindOf] for these until a follow-up wave wires the
 *    consumer + adds the constant lookup. The cost of pre-mapping with
 *    no consumer is wasted dispatch work on every packet — explicit
 *    `null` return for unconsumed kinds keeps the hot path cheap.
 *
 * ## Threading
 *
 * PacketEvents fires `onPacketReceive` / `onPacketSend` from its Netty
 * dispatcher thread (NOT the Paper main thread). Listeners registered
 * through [register] therefore run off-main. Per the existing
 * [IPacketInterceptor] contract (Wave 5y, ADR-0095): listeners MUST be
 * thread-safe and MUST NOT touch Bukkit APIs that require the main thread
 * (use `scheduler.runLater(Duration.ZERO)` to marshal back).
 *
 * The [entries] list is a [CopyOnWriteArrayList] so [register] / [unregister]
 * from any thread don't tear the iteration in [dispatch].
 *
 * ## Replace decision
 *
 * [PacketDecision.Replace] is **not yet implemented** in this adapter. A
 * generic mutation of an arbitrary `PacketWrapper<*>` would either need a
 * typed Replace handler per [PacketKind] (so the adapter knows which fields
 * to overwrite — ADR-0095 conventions: `x`, `y`, `z` for MovementClientbound
 * coordinate masking, etc.) or runtime reflection on the wrapper. Phase 1
 * PoC has no Replace consumer (the InMemory variant's payload-merge path
 * services tests only), so the Paper adapter logs INFO once per server
 * lifetime and treats Replace as [PacketDecision.Pass] — visibly degrading
 * rather than silently misbehaving. A follow-up wave (ADR-0170 §future-work)
 * lands typed Replace handlers as consumers arrive.
 */
public class PaperPacketInterceptor(
    private val tickProvider: () -> Long,
) : IPacketInterceptor, PacketListener {

    private val log = LoggerFactory.getLogger(javaClass)

    private class Entry(
        val kind: PacketKind,
        val listener: (PacketEnvelope) -> PacketDecision,
        @Volatile var active: Boolean = true,
    )

    private val entries: MutableList<Entry> = CopyOnWriteArrayList()

    @Volatile
    private var replaceUnsupportedLogged: Boolean = false

    /**
     * Register this listener with PacketEvents' event manager. Call from
     * `AsteraCorePlugin.onEnable` after `PacketEvents.getAPI().init()`.
     * Idempotent only in the sense that re-registration adds a duplicate
     * listener entry on PE's side — callers should not invoke this twice.
     */
    public fun start() {
        PacketEvents.getAPI().eventManager.registerListener(this, PacketListenerPriority.NORMAL)
        log.info("PaperPacketInterceptor: registered with PacketEvents (NORMAL priority).")
    }

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

    override fun onPacketReceive(event: PacketReceiveEvent) {
        dispatch(event, inboundKindOf(event.packetType))
    }

    override fun onPacketSend(event: PacketSendEvent) {
        dispatch(event, outboundKindOf(event.packetType))
    }

    private fun dispatch(event: ProtocolPacketEvent, kind: PacketKind?) {
        if (kind == null) return
        // Fast-path early-out: avoid envelope construction when no listener
        // registered the kind. Common case (most packets have no consumer).
        if (entries.none { it.active && it.kind == kind }) return
        val envelope = buildEnvelope(kind, event) ?: return
        for (entry in entries) {
            if (!entry.active || entry.kind != kind) continue
            when (entry.listener(envelope)) {
                PacketDecision.Pass -> Unit
                PacketDecision.Cancel -> {
                    event.setCancelled(true)
                    return
                }
                is PacketDecision.Replace -> {
                    if (!replaceUnsupportedLogged) {
                        log.info(
                            "PaperPacketInterceptor: PacketDecision.Replace is not yet implemented " +
                                "by the Paper adapter (no generic PacketWrapper mutation path; field " +
                                "names diverge per PE major version). Treating as Pass — typed " +
                                "Replace handlers land in a follow-up wave per ADR-0170 §future-work.",
                        )
                        replaceUnsupportedLogged = true
                    }
                }
            }
        }
    }

    private fun buildEnvelope(kind: PacketKind, event: ProtocolPacketEvent): PacketEnvelope? {
        val playerUuid = event.user?.uuid ?: return null
        return PacketEnvelope(
            kind = kind,
            playerId = PlayerId(playerUuid),
            // Phase 1 PoC: empty payload. The current Astera consumer
            // (MovementValidatorPacketBridge) does not read x/y/z from
            // the envelope — it pulls the live position from
            // `Player.location` via the orchestrator instead. Future
            // listeners that genuinely need wire-level fields will get
            // typed buildEnvelope helpers per kind.
            payloadView = emptyMap(),
            atTick = tickProvider(),
        )
    }

    private fun inboundKindOf(type: PacketTypeCommon): PacketKind? = when (type) {
        PacketType.Play.Client.PLAYER_POSITION,
        PacketType.Play.Client.PLAYER_ROTATION,
        PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> PacketKind.MovementServerbound
        else -> null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun outboundKindOf(type: PacketTypeCommon): PacketKind? {
        // Reserved for future waves. Returning null short-circuits dispatch
        // so PacketEvents events for outbound packets cost ~zero in the
        // current build (one CopyOnWriteArrayList.none() check + return).
        // Wave 6c-stub-packetevents-N1 (per-viewer scoreboard / tablist),
        // Wave 6c-stub-packetevents-N2 (visibility EntitySpawn / Destroy
        // drops), and Wave 5w (replay coordinate masking) each add a
        // branch + their consumer.
        return null
    }
}
