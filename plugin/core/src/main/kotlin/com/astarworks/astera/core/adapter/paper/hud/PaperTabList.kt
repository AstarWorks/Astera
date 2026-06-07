package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.adapter.paper.packetevents.ITabListPacketSender
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.entity.Player

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
 * Phase 1 PoC limits (still present with the sender wired):
 *  - Adding **non-online-player** entries (synthetic NPC rows, custom
 *    server-info badges) requires phantom `UserProfile` UUIDs with
 *    their own lifecycle (ADD_PLAYER on first sight, REMOVE on diff)
 *    and is deferred to a follow-up wave (ADR-0172 §carve-outs).
 *    Offline / synthetic rows continue to be dropped silently — only
 *    recorded in the snapshot returned by [currentEntries].
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

    override fun setHeader(playerId: PlayerId, header: MessageKey, placeholders: Map<String, String>) {
        val rendered = render(playerId, header, placeholders)
        headerKeys[playerId] = RenderedRecord(header, placeholders, rendered)
        server.getPlayer(playerId.uuid)?.sendPlayerListHeader(Component.text(rendered))
    }

    override fun setFooter(playerId: PlayerId, footer: MessageKey, placeholders: Map<String, String>) {
        val rendered = render(playerId, footer, placeholders)
        footerKeys[playerId] = RenderedRecord(footer, placeholders, rendered)
        server.getPlayer(playerId.uuid)?.sendPlayerListFooter(Component.text(rendered))
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
        pushEntriesForOnlineSubjects(playerId, entries)
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
            server.getPlayer(playerId.uuid)?.sendPlayerListHeader(Component.text(rendered))
        }
        footerKeys.forEach { (playerId, rec) ->
            val rendered = render(playerId, rec.key, rec.placeholders)
            footerKeys[playerId] = rec.copy(rendered = rendered)
            server.getPlayer(playerId.uuid)?.sendPlayerListFooter(Component.text(rendered))
        }
        entries.forEach { (playerId, list) -> pushEntriesForOnlineSubjects(playerId, list) }
    }

    private fun pushEntriesForOnlineSubjects(playerId: PlayerId, list: List<TabListEntry>) {
        // Wave 6c-stub-packetevents-C (ADR-0172): when the per-viewer
        // packet sender is wired, route through it so each viewer gets
        // its own UPDATE_DISPLAY_NAME / UPDATE_LATENCY packets and
        // server-wide `Player.playerListName` is left untouched (no
        // leak to other viewers). When the sender is null (vanilla
        // Paper, PacketEvents absent), fall back to the original
        // server-global `playerListName` set so dev-time / non-PE
        // deployments still work — at the cost of one viewer's
        // rename leaking to everyone, the known limitation.
        val sender = tabListPacketSender
        val viewer: Player? = sender?.let { server.getPlayer(playerId.uuid) }
        for (entry in list) {
            val subject: Player = server.getPlayerExact(entry.name) ?: continue
            val displayKey = entry.displayName
            val displayText = if (displayKey != null) {
                render(playerId, displayKey, emptyMap())
            } else {
                entry.name
            }
            if (sender != null && viewer != null) {
                sender.sendDisplayNameUpdate(viewer, subject.uniqueId, Component.text(displayText))
                sender.sendLatencyUpdate(viewer, subject.uniqueId, entry.pingMs)
            } else {
                subject.playerListName(Component.text(displayText))
            }
        }
    }

    private fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
        messageRenderer?.render(playerId, key, placeholders)
            ?: naiveRender(key, placeholders)

    private fun naiveRender(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var out = key.value
        for ((name, value) in placeholders) out = out.replace("{$name}", value)
        return out
    }

    private data class RenderedRecord(
        val key: MessageKey,
        val placeholders: Map<String, String>,
        val rendered: String,
    )
}
