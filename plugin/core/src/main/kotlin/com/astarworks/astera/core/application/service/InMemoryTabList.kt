package com.astarworks.astera.core.application.service

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Wave 5b default [ITabList] registered by Core at priority 0.
 *
 * Per-player header / footer / entries — no cross-player broadcast.
 * Header / footer are stored already-rendered (naive `{name}`
 * substitution); the Wave 6 Paper impl swaps that for MiniMessage +
 * `IMessageRenderer` locale-aware lookup at the boundary.
 */
public class InMemoryTabList : ITabList {

    private val headers: MutableMap<PlayerId, String> = mutableMapOf()
    private val footers: MutableMap<PlayerId, String> = mutableMapOf()
    private val entries: MutableMap<PlayerId, List<TabListEntry>> = mutableMapOf()

    override fun setHeader(playerId: PlayerId, header: MessageKey, placeholders: Map<String, String>) {
        headers[playerId] = render(header, placeholders)
    }

    override fun setFooter(playerId: PlayerId, footer: MessageKey, placeholders: Map<String, String>) {
        footers[playerId] = render(footer, placeholders)
    }

    override fun clearHeader(playerId: PlayerId) { headers.remove(playerId) }
    override fun clearFooter(playerId: PlayerId) { footers.remove(playerId) }

    override fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>) {
        this.entries[playerId] = entries.toList()
    }

    override fun currentHeader(playerId: PlayerId): String? = headers[playerId]
    override fun currentFooter(playerId: PlayerId): String? = footers[playerId]

    override fun currentEntries(playerId: PlayerId): List<TabListEntry> =
        entries[playerId]?.toList() ?: emptyList()

    private fun render(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var result: String = key.value
        for ((name, value) in placeholders) result = result.replace("{$name}", value)
        return result
    }
}
