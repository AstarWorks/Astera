package com.astarworks.astera.core.adapter.paper.hud

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTabRow
import com.astarworks.astera.core.adapter.api.hud.ITabList
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.adapter.api.hud.TabListEntry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/** Viewer-local tab model. Header, footer, real rows and phantom rows all use PacketSink. */
public class PaperTabList(
    private val server: Server,
    private val messageRenderer: IMessageRenderer,
    private val sink: PacketSink,
) : ITabList, Listener {
    private data class Text(val key: MessageKey, val placeholders: Map<String, String>)
    private val headers = mutableMapOf<PlayerId, Text>()
    private val footers = mutableMapOf<PlayerId, Text>()
    private val entries = mutableMapOf<PlayerId, List<TabListEntry>>()

    @Synchronized
    override fun setHeader(playerId: PlayerId, header: MessageKey, placeholders: Map<String, String>) {
        headers[playerId] = Text(header, placeholders.toMap())
        draw(playerId)
    }

    @Synchronized
    override fun setFooter(playerId: PlayerId, footer: MessageKey, placeholders: Map<String, String>) {
        footers[playerId] = Text(footer, placeholders.toMap())
        draw(playerId)
    }

    @Synchronized
    override fun clearHeader(playerId: PlayerId) {
        headers.remove(playerId)
        draw(playerId)
    }

    @Synchronized
    override fun clearFooter(playerId: PlayerId) {
        footers.remove(playerId)
        draw(playerId)
    }

    @Synchronized
    override fun setPlayerEntries(playerId: PlayerId, entries: List<TabListEntry>) {
        this.entries[playerId] = entries.toList()
        draw(playerId)
    }

    @Synchronized
    override fun currentHeader(playerId: PlayerId): String? = headers[playerId]?.let { render(playerId, it) }

    @Synchronized
    override fun currentFooter(playerId: PlayerId): String? = footers[playerId]?.let { render(playerId, it) }

    @Synchronized
    override fun currentEntries(playerId: PlayerId): List<TabListEntry> = entries[playerId].orEmpty().toList()

    @Synchronized
    public fun refresh() { (headers.keys + footers.keys + entries.keys).forEach(::draw) }

    @EventHandler(priority = EventPriority.MONITOR)
    @Synchronized
    public fun onQuit(event: PlayerQuitEvent) {
        val playerId = PlayerId(event.player.uniqueId)
        headers.remove(playerId)
        footers.remove(playerId)
        entries.remove(playerId)
    }

    private fun render(viewer: PlayerId, text: Text): String = messageRenderer.render(viewer, text.key, text.placeholders)

    private fun draw(viewer: PlayerId) {
        sink.submit(viewer, HudSurface.TAB, HudFrame(
            header = headers[viewer]?.let { render(viewer, it) }.orEmpty(),
            footer = footers[viewer]?.let { render(viewer, it) }.orEmpty(),
            rows = entries[viewer].orEmpty().associate { entry ->
                val subject = server.getPlayerExact(entry.name)
                val uuid = subject?.uniqueId ?: phantomTabListUuid(viewer.uuid, entry.name)
                uuid to HudTabRow(entry.name,
                    entry.displayName?.let { messageRenderer.render(viewer, it) } ?: entry.name,
                    entry.pingMs.coerceAtLeast(0), subject == null)
            },
        ))
    }
}

internal fun phantomTabListUuid(viewerUuid: UUID, name: String): UUID =
    UUID.nameUUIDFromBytes("$viewerUuid:$name".toByteArray())
