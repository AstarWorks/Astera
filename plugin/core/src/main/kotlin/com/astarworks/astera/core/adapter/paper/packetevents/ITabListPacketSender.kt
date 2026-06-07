package com.astarworks.astera.core.adapter.paper.packetevents

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Per-viewer tab-list packet override hook (ADR-0172 Wave 6c-stub-
 * packetevents-C).
 *
 * Implementations send a clientbound player-info UPDATE packet to
 * [viewer] **only** — the rest of the server keeps whatever the shared
 * Player List packet last broadcast. This is the seam that lets
 * `PaperTabList` render each viewer's tab-list rows in their own
 * locale (and push the per-row ping badge that vanilla
 * `Player.playerListName` doesn't expose).
 *
 * ## Isolation contract (mirror of [ITeamPacketSender])
 *
 * This interface deliberately uses only Bukkit / Adventure / `java.util`
 * types — never a PacketEvents symbol — so consumers stay loadable on
 * vanilla Paper (no `NoClassDefFoundError` per JLS §5.3.4). The
 * PacketEvents-backed implementation
 * ([PacketEventsTabListPacketSender]) is the **only** class allowed to
 * import `com.github.retrooper.packetevents.*`, and it is constructed
 * exclusively inside `AsteraCorePlugin`'s `if (packetEventsInstalled)`
 * gate. When PacketEvents is absent the consumer (`PaperTabList`)
 * receives `null` and skips the per-viewer fan-out entirely — vanilla
 * `Player.playerListName` continues to serve as the server-wide
 * fallback.
 *
 * ## Why only existing subjects
 *
 * The Phase 1 PoC scope here covers per-viewer display-name + latency
 * overrides for subjects the viewer **already has** in their tab list
 * (i.e. online players the server-side Player List packet already
 * spawned a row for). Injecting *synthetic* tab-list rows for offline /
 * NPC subjects requires phantom `UserProfile` UUIDs with their own
 * lifecycle (ADD_PLAYER on first sight, REMOVE on `setPlayerEntries`
 * diff) and is deferred to a follow-up wave per ADR-0172 §carve-outs.
 *
 * ## Threading
 *
 * Safe from any thread (the PacketEvents send path ends in a Netty
 * `writeAndFlush`). Astera's tab-list mutations run on the main server
 * thread, so callers do not need to marshal.
 */
public interface ITabListPacketSender {

    /**
     * Override the tab-list display name of the subject identified by
     * [subjectUuid] **for [viewer] only**. [displayName] becomes the
     * row text the viewer sees. The subject's server-side
     * `Player.playerListName` is untouched, so other viewers continue
     * to see whatever was last broadcast to them.
     *
     * Caller is responsible for ensuring the viewer's client already
     * holds a tab-list row for [subjectUuid] (online subject path
     * only). For offline / synthetic subjects this method is a no-op
     * from the client's perspective.
     */
    public fun sendDisplayNameUpdate(viewer: Player, subjectUuid: UUID, displayName: Component)

    /**
     * Override the latency badge of the subject identified by
     * [subjectUuid] **for [viewer] only**. [latencyMs] feeds the
     * standard 5-bar ping icon (≤150ms = 5 bars, etc.).
     *
     * Negative values are coerced to 0 (the icon clamps to "1 bar /
     * connecting" at 0 latency).
     */
    public fun sendLatencyUpdate(viewer: Player, subjectUuid: UUID, latencyMs: Int)
}
