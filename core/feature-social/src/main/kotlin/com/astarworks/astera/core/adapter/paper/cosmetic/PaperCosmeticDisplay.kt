package com.astarworks.astera.core.adapter.paper.cosmetic

import com.astarworks.astera.core.adapter.api.hud.HudFrame
import com.astarworks.astera.core.adapter.api.hud.HudSurface
import com.astarworks.astera.core.adapter.api.hud.HudTabRow
import com.astarworks.astera.core.adapter.api.hud.PacketSink
import com.astarworks.astera.core.application.port.outbound.ExecutionScope
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.application.port.outbound.ticks
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Shows the equipped title and name colour in the tab list (ECO-02). Chat uses the same helpers
 * through [PaperChatFormatListener][com.astarworks.astera.core.adapter.paper.chat.PaperChatFormatListener].
 * Publishes viewer-local packet models; the sink owns Entity dispatch and client diffs.
 */
public class PaperCosmeticDisplay(
    private val server: Server,
    private val sink: PacketSink,
    private val scheduler: IScheduler,
    private val cosmetics: () -> CosmeticService?,
    private val messages: () -> IMessageRenderer?,
) : Listener {

    @EventHandler
    public fun onJoin(event: PlayerJoinEvent) {
        val id = PlayerId(event.player.uniqueId)
        // Player-info ADD must reach the new client before any display-name override.
        scheduler.run(ExecutionScope.Entity(id.uuid), ticks(1)) { refresh(id) }
    }

    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) { refreshAll(PlayerId(event.player.uniqueId)) }

    public fun refresh(playerId: PlayerId) {
        if (server.getPlayer(playerId.uuid) != null) refreshAll()
    }

    private fun refreshAll(exclude: PlayerId? = null) {
        val service = cosmetics() ?: return
        val online = server.onlinePlayers.filter { it.uniqueId != exclude?.uuid }.map { it.uniqueId to it.name }
        val mini = MiniMessage.miniMessage()
        for ((viewerUuid, _) in online) {
            val viewer = PlayerId(viewerUuid)
            sink.submit(viewer, HudSurface.COSMETIC_TAB, HudFrame(rows = online.associate { (uuid, name) ->
                val subject = PlayerId(uuid)
                val title = titleComponent(service, messages(), subject, viewer)
                val color = nameColorOf(service, subject)
                val display = title.append(Component.text(name, color ?: NamedTextColor.WHITE))
                uuid to HudTabRow(name, mini.serialize(display), null, false)
            }))
        }
    }

    public companion object {
        /** `[Title] ` in gold, or empty when no title is equipped. */
        public fun titleComponent(service: CosmeticService?, messages: IMessageRenderer?, playerId: PlayerId, viewer: PlayerId?): Component {
            val title = service?.equipped(playerId, CosmeticKind.TITLE) ?: return Component.empty()
            val text = messages?.render(viewer, title.nameKey) ?: title.id
            return Component.text("[$text] ", NamedTextColor.GOLD)
        }

        /** The equipped name colour, or null when none. */
        public fun nameColorOf(service: CosmeticService?, playerId: PlayerId): TextColor? =
            service?.equipped(playerId, CosmeticKind.NAME_COLOR)?.let { parseColor(it.value) }

        public fun parseColor(value: String): TextColor? =
            if (value.startsWith("#")) TextColor.fromHexString(value) else NamedTextColor.NAMES.value(value.lowercase())
    }
}
