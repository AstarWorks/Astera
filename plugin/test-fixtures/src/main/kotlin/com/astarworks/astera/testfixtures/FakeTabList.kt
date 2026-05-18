package com.astarworks.astera.testfixtures

import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * In-memory [ITabList] for application-layer tests.
 *
 * - Stores header / footer text per [PlayerId] after running placeholder
 *   substitution via [render]. The substitution is intentionally naive —
 *   real localization is the Wave 6 Paper adapter's job (MiniMessage +
 *   `IMessageRenderer`).
 * - Stores the rendered text rather than the raw key so [currentHeader]
 *   / [currentFooter] return values that already reflect any placeholder
 *   inputs the caller supplied.
 * - Each player's state is independent; the maps are keyed by [PlayerId]
 *   and there is no cross-player broadcast.
 */
public class FakeTabList : ITabList {

    private val headers: MutableMap<PlayerId, String> = mutableMapOf()
    private val footers: MutableMap<PlayerId, String> = mutableMapOf()
    private val entries: MutableMap<PlayerId, List<TabListEntry>> = mutableMapOf()

    override fun setHeader(
        playerId: PlayerId,
        header: MessageKey,
        placeholders: Map<String, String>,
    ) {
        headers[playerId] = render(header, placeholders)
    }

    override fun setFooter(
        playerId: PlayerId,
        footer: MessageKey,
        placeholders: Map<String, String>,
    ) {
        footers[playerId] = render(footer, placeholders)
    }

    override fun clearHeader(playerId: PlayerId) {
        headers.remove(playerId)
    }

    override fun clearFooter(playerId: PlayerId) {
        footers.remove(playerId)
    }

    override fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>) {
        this.entries[playerId] = entries.toList()
    }

    override fun currentHeader(playerId: PlayerId): String? = headers[playerId]

    override fun currentFooter(playerId: PlayerId): String? = footers[playerId]

    override fun currentEntries(playerId: PlayerId): List<TabListEntry> =
        entries[playerId]?.toList() ?: emptyList()

    /**
     * Naive `{name}` substitution into [key]`.value`. Unknown `{...}`
     * segments are left intact — easier to spot a missing placeholder in
     * a failing test than to silently swallow it.
     */
    private fun render(key: MessageKey, placeholders: Map<String, String>): String {
        if (placeholders.isEmpty()) return key.value
        var result: String = key.value
        for ((name, value) in placeholders) {
            result = result.replace("{$name}", value)
        }
        return result
    }
}
