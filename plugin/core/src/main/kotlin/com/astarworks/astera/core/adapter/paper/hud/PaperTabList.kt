package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.adapter.paper.packetevents.ITabListPacketSender
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Server
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Wave 6b Paper [ITabList] registered at priority 100, replacing the
 * Wave 5b `InMemoryTabList`.
 *
 * Header and footer route through Adventure's
 * `Player.sendPlayerListHeader(Component)` /
 * `sendPlayerListFooter(Component)`. Per-player tab name override uses
 * `Player.playerListName(Component)` for entries whose [TabListEntry.name]
 * matches an online player — this is the only per-row override the
 * Paper-bundled Adventure API supports without packet-level work.
 *
 * # Per-viewer rendering (Wave 6c-stub-packetevents-C, ADR-0172)
 *
 * When the optional [tabListPacketSender] is wired (PacketEvents
 * installed), [setPlayerEntries] fans a per-viewer
 * `WrapperPlayServerPlayerInfoUpdate` out to the requesting viewer so
 * **each player sees their own locale-rendered display names + ping
 * badges** for the listed online subjects. The server-wide
 * `Player.playerListName` is left untouched in this branch, so other
 * viewers continue to see whatever the shared Player List packet last
 * broadcast — no leak. When [tabListPacketSender] is `null` (vanilla
 * Paper, no PE), `setPlayerEntries` falls back to the original
 * Wave 6b behaviour (server-wide `Player.playerListName` for online
 * subjects, offline / synthetic rows silently dropped).
 *
 * # Synthetic / offline rows (Wave 6c-stub-packetevents-D, ADR-0173)
 *
 * With the sender wired, an entry whose [TabListEntry.name] is **not** an
 * online player is injected as a **phantom** tab-list row for the
 * requesting viewer only: a per-viewer phantom UUID
 * ([phantomTabListUuid]) is ADD_PLAYER'd carrying the viewer's
 * locale-rendered display name + ping badge. Phantom UUIDs that drop out
 * of a later `setPlayerEntries` push are REMOVE'd. The phantom row has no
 * skin (default steve/alex head). Without the sender (vanilla Paper, no
 * PE) synthetic rows are still dropped — there is no client baseline to
 * override.
 *
 * Phase 1 PoC limits (still present with the sender wired):
 *  - Duplicate offline entries sharing the same [TabListEntry.name]
 *    collapse to one phantom row (the derived UUID is name-scoped).
 *  - Header / footer rendering uses [IMessageRenderer] per-viewer when
 *    supplied (existing). Without a renderer the raw [MessageKey]
 *    value is used with in-process `{name}` substitution.
 *
 * Threading: mutating methods (setHeader / setFooter / clear* /
 * setPlayerEntries / [refresh]) must run on the main server thread.
 * State maps are not synchronized. The packet send path itself is
 * thread-safe (`User.sendPacket` ends in a Netty `writeAndFlush`).
 */
public class PaperTabList(
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
    private val tabListPacketSender: ITabListPacketSender? = null,
) : ITabList {

    private val headerKeys: MutableMap<PlayerId, RenderedRecord> = mutableMapOf()
    private val footerKeys: MutableMap<PlayerId, RenderedRecord> = mutableMapOf()
    private val entries: MutableMap<PlayerId, List<TabListEntry>> = mutableMapOf()

    // Wave 6c-stub-packetevents-D (ADR-0173): phantom tab-list-row UUIDs
    // currently injected for each viewer, so the next push can REMOVE the
    // ones that dropped out of the entry-set. Only populated when the
    // packet sender is wired (vanilla Paper can't inject synthetic rows).
    private val phantomUuids: MutableMap<PlayerId, Set<UUID>> = mutableMapOf()

    override fun setHeader(playerId: PlayerId, header: MessageKey, placeholders: Map<String, String>) {
        val rendered = render(playerId, header, placeholders)
        headerKeys[playerId] = RenderedRecord(header, placeholders, rendered)
        server.getPlayer(playerId.uuid)?.sendPlayerListHeader(toComponent(rendered))
    }

    override fun setFooter(playerId: PlayerId, footer: MessageKey, placeholders: Map<String, String>) {
        val rendered = render(playerId, footer, placeholders)
        footerKeys[playerId] = RenderedRecord(footer, placeholders, rendered)
        server.getPlayer(playerId.uuid)?.sendPlayerListFooter(toComponent(rendered))
    }

    override fun clearHeader(playerId: PlayerId) {
        headerKeys.remove(playerId) ?: return
        server.getPlayer(playerId.uuid)?.sendPlayerListHeader(Component.empty())
    }

    override fun clearFooter(playerId: PlayerId) {
        footerKeys.remove(playerId) ?: return
        server.getPlayer(playerId.uuid)?.sendPlayerListFooter(Component.empty())
    }

    override fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>) {
        this.entries[playerId] = entries.toList()
        pushEntries(playerId, entries)
    }

    override fun currentHeader(playerId: PlayerId): String? = headerKeys[playerId]?.rendered

    override fun currentFooter(playerId: PlayerId): String? = footerKeys[playerId]?.rendered

    override fun currentEntries(playerId: PlayerId): List<TabListEntry> =
        entries[playerId]?.toList() ?: emptyList()

    /**
     * Re-render every viewer's header / footer and push the result.
     *
     * Useful after a locale switch (the cached [RenderedRecord] is
     * recomputed) or after the renderer's underlying catalog reload.
     * No-op for viewers without an online `Player`.
     */
    public fun refresh() {
        headerKeys.forEach { (playerId, rec) ->
            val rendered = render(playerId, rec.key, rec.placeholders)
            headerKeys[playerId] = rec.copy(rendered = rendered)
            server.getPlayer(playerId.uuid)?.sendPlayerListHeader(toComponent(rendered))
        }
        footerKeys.forEach { (playerId, rec) ->
            val rendered = render(playerId, rec.key, rec.placeholders)
            footerKeys[playerId] = rec.copy(rendered = rendered)
            server.getPlayer(playerId.uuid)?.sendPlayerListFooter(toComponent(rendered))
        }
        entries.forEach { (playerId, list) -> pushEntries(playerId, list) }
    }

    private fun pushEntries(playerId: PlayerId, list: List<TabListEntry>) {
        // Wave 6c-stub-packetevents-C (ADR-0172): when the per-viewer
        // packet sender is wired, route online subjects through it so
        // each viewer gets its own UPDATE_DISPLAY_NAME / UPDATE_LATENCY
        // packets and server-wide `Player.playerListName` is left
        // untouched (no leak to other viewers). When the sender is null
        // (vanilla Paper, PacketEvents absent), fall back to the
        // server-global `playerListName` set so dev-time / non-PE
        // deployments still work — at the cost of one viewer's rename
        // leaking to everyone, the known limitation.
        //
        // Wave 6c-stub-packetevents-D (ADR-0173): offline / synthetic
        // subjects (no online `Player`, no client baseline row) are
        // injected as per-viewer phantom rows via ADD_PLAYER, and the
        // phantom UUIDs that drop out of the entry-set are REMOVE'd. This
        // needs the packet sender — vanilla Paper still drops them.
        val sender = tabListPacketSender
        val viewer: Player? = sender?.let { server.getPlayer(playerId.uuid) }
        val desiredPhantoms = mutableSetOf<UUID>()
        for (entry in list) {
            val displayText = entry.displayName?.let { render(playerId, it, emptyMap()) } ?: entry.name
            val subject: Player? = server.getPlayerExact(entry.name)
            when {
                // Online subject: override its existing tab-list row.
                subject != null -> {
                    if (sender != null && viewer != null) {
                        sender.sendDisplayNameUpdate(viewer, subject.uniqueId, toComponent(displayText))
                        sender.sendLatencyUpdate(viewer, subject.uniqueId, entry.pingMs)
                    } else {
                        subject.playerListName(toComponent(displayText))
                    }
                }
                // Offline / synthetic subject: inject a per-viewer phantom
                // row. Impossible without the sender (the client has no
                // baseline to override), so vanilla Paper drops it.
                sender != null && viewer != null -> {
                    val phantom = phantomTabListUuid(viewer.uniqueId, entry.name)
                    sender.sendAddEntry(viewer, phantom, entry.name, toComponent(displayText), entry.pingMs)
                    desiredPhantoms += phantom
                }
            }
        }
        // Reconcile phantom rows: REMOVE any injected on a prior push that
        // are no longer desired. ADD is re-sent above every push (idempotent
        // refresh), so the tracked set drives removals only. Skip when the
        // viewer is offline — nothing to send; the set is reconciled on the
        // next push after they (re)connect.
        if (sender != null && viewer != null) {
            val previous = phantomUuids[playerId].orEmpty()
            for (stale in previous - desiredPhantoms) {
                sender.sendRemoveEntry(viewer, stale)
            }
            phantomUuids[playerId] = desiredPhantoms
        }
    }

    /** Rendered catalogue text carries MiniMessage tags (`<gold>..`), like every other HUD surface. */
    private fun toComponent(rendered: String): Component = MINI.deserialize(rendered)

    private fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
        messageRenderer?.render(playerId, key, placeholders)
            ?: naiveRender(key, placeholders)

    private fun naiveRender(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var out = key.value
        for ((name, value) in placeholders) out = out.replace("{$name}", value)
        return out
    }

    private companion object {
        val MINI: MiniMessage = MiniMessage.miniMessage()
    }

    private data class RenderedRecord(
        val key: MessageKey,
        val placeholders: Map<String, String>,
        val rendered: String,
    )
}

/**
 * Stable per-viewer phantom UUID for a synthetic tab-list row
 * (Wave 6c-stub-packetevents-D, ADR-0173).
 *
 * Derived from the viewer UUID + row name so that:
 *  - the same row keeps the same UUID across pushes — ADD is idempotent
 *    and REMOVE targets exactly the row it spawned;
 *  - two viewers' phantom rows never collide — one viewer's REMOVE can
 *    never tear down another viewer's row.
 *
 * A type-3 (name-based) UUID over a viewer-scoped string; it does not
 * collide with a real player's type-4 UUID in practice. Pure — no Bukkit,
 * unit-tested directly.
 */
internal fun phantomTabListUuid(viewerUuid: UUID, name: String): UUID =
    UUID.nameUUIDFromBytes("$viewerUuid:$name".toByteArray())
