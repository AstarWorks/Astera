package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
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
 * Phase 1 PoC limits:
 *  - Adding **non-online-player** entries (e.g. synthetic NPC rows,
 *    custom server-info badges) is **not supported** by vanilla / Paper
 *    Bukkit. Such entries are silently dropped from the per-row push and
 *    recorded only in the snapshot returned by [currentEntries]. Wave
 *    6b-stub introduces PacketEvents-based fake-entry injection, at
 *    which point this class will be superseded or its `setPlayerEntries`
 *    will gain a packet fan-out branch.
 *  - [TabListEntry.pingMs] is recorded in the snapshot but **not pushed**
 *    — vanilla `Player.setPlayerListName` does not expose the ping
 *    badge. Same dep on PacketEvents.
 *  - Header / footer rendering uses [IMessageRenderer] per-viewer when
 *    supplied. Without a renderer, the raw [MessageKey] value is used
 *    with in-process `{name}` substitution so dev-time use still works.
 *
 * Threading: mutating methods (setHeader / setFooter / clear* /
 * setPlayerEntries / [refresh]) must run on the main server thread.
 * State maps are not synchronized.
 */
public class PaperTabList(
    private val server: Server,
    private val messageRenderer: IMessageRenderer? = null,
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
        // The vanilla Player List packet is server-global per *subject*
        // (the player whose row is being edited), not per-viewer. We can
        // only set the display name of subjects that are online; entries
        // for offline players / synthetic rows are skipped (recorded in
        // the snapshot only). True per-viewer rows need PacketEvents.
        for (entry in list) {
            val subject: Player = server.getPlayerExact(entry.name) ?: continue
            val displayKey = entry.displayName
            val displayText = if (displayKey != null) {
                render(playerId, displayKey, emptyMap())
            } else {
                entry.name
            }
            subject.playerListName(Component.text(displayText))
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
