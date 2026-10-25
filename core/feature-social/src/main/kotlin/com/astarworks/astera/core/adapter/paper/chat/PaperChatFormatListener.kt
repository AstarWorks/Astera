package com.astarworks.astera.core.adapter.paper.chat

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.domain.player.PlayerId
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * Chat line format (ADR-0206): `Name » message`, the name in the sender's team colour
 * while they are in a match (so a chat line reads like the nameplate above their head),
 * grey in the lobby, the message in white.
 *
 * Only the *rendering* changes; routing, mutes and signing stay vanilla / Paper. The
 * team lookup runs on the async chat thread, so it is wrapped: any failure falls back
 * to the lobby look instead of losing the message.
 *
 * Both lookups are suppliers because the match plugin registers its repository after
 * core has enabled.
 */
public class PaperChatFormatListener(
    private val matches: () -> IMatchRepository?,
    private val messages: () -> IMessageRenderer?,
) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public fun onChat(event: AsyncChatEvent) {
        val nameColor = nameColorOf(PlayerId(event.player.uniqueId))
        event.renderer(
            ChatRenderer.viewerUnaware { _, sourceDisplayName, message ->
                Component.text()
                    .append(sourceDisplayName.colorIfAbsent(nameColor))
                    .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                    .append(message.colorIfAbsent(NamedTextColor.WHITE))
                    .build()
            },
        )
    }

    private fun nameColorOf(player: PlayerId): TextColor = try {
        val match = matches()?.findMatchOf(player)
        val team = match?.teams?.firstOrNull { player in it.members }
        val colorName = team?.let { messages()?.render(null, it.colorKey) }
        colorName?.let { NamedTextColor.NAMES.value(it.lowercase()) } ?: LOBBY_COLOR
    } catch (_: RuntimeException) {
        LOBBY_COLOR
    }

    private companion object {
        val LOBBY_COLOR: TextColor = NamedTextColor.GRAY
    }
}
