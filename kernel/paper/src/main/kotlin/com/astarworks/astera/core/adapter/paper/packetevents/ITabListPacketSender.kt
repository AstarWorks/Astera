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
 * ## Two surfaces: online overrides + synthetic rows
 *
 * - **Online subjects** ([sendDisplayNameUpdate] / [sendLatencyUpdate],
 *   ADR-0172): per-viewer display-name + latency overrides for subjects
 *   the viewer **already has** in their tab list (online players the
 *   shared Player List packet already spawned a row for). The real
 *   `UserProfile` UUID is reused — these UPDATE the existing row.
 * - **Synthetic / offline subjects** ([sendAddEntry] / [sendRemoveEntry],
 *   ADR-0173 Wave 6c-stub-packetevents-D): rows the viewer's client has
 *   **no** baseline for (NPC rows, server-info badges, offline players).
 *   These need a **phantom** `UserProfile` UUID injected with its own
 *   lifecycle — ADD_PLAYER (+ UPDATE_LISTED) on (re)push, REMOVE when the
 *   row drops out of the next `setPlayerEntries` diff. The phantom UUID
 *   is caller-derived and **per-viewer** (so one viewer's phantom row
 *   never collides with another's): `PaperTabList` uses
 *   `UUID.nameUUIDFromBytes("$viewerUuid:$entryName")`. Never pass a real
 *   player's UUID to [sendAddEntry] / [sendRemoveEntry].
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

    /**
     * Inject (or refresh) a **synthetic** tab-list row for [viewer]
     * only — a row the viewer's client has no baseline for (NPC rows,
     * server-info badges, offline players). [entryUuid] must be a
     * caller-derived **phantom** UUID, unique per (viewer, row); never a
     * real player's UUID. [name] is the profile name, [displayName] the
     * rendered row text the viewer sees, [latencyMs] the ping badge
     * (negative coerced to 0).
     *
     * Idempotent: re-invoking for the same [entryUuid] updates the
     * existing phantom row in place (the underlying ADD_PLAYER carries
     * the current display name + latency). The synthetic profile has no
     * skin, so the row shows the default (steve/alex) head — acceptable
     * for the Phase 1 PoC.
     */
    public fun sendAddEntry(
        viewer: Player,
        entryUuid: UUID,
        name: String,
        displayName: Component,
        latencyMs: Int,
    )

    /**
     * Remove a phantom row previously injected via [sendAddEntry] from
     * [viewer]'s tab list. [entryUuid] is the same caller-derived
     * phantom UUID. No-op from the client's perspective if the viewer
     * never held that row.
     */
    public fun sendRemoveEntry(viewer: Player, entryUuid: UUID)
}
