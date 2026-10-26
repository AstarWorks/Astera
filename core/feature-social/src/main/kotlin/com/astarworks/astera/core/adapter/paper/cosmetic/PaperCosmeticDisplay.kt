package com.astarworks.astera.core.adapter.paper.cosmetic

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.domain.cosmetic.CosmeticKind
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Server
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Shows the equipped title and name colour in the tab list (ECO-02). Chat uses the same helpers
 * through [PaperChatFormatListener][com.astarworks.astera.core.adapter.paper.chat.PaperChatFormatListener].
 * Must run on the main thread (join event / GUI click).
 */
public class PaperCosmeticDisplay(
    private val server: Server,
    private val cosmetics: () -> CosmeticService?,
    private val messages: () -> IMessageRenderer?,
) : Listener {

    @EventHandler
    public suspend fun onJoin(event: PlayerJoinEvent) {
        refresh(PlayerId(event.player.uniqueId))
    }

    public suspend fun refresh(playerId: PlayerId) {
        val player = server.getPlayer(playerId.uuid) ?: return
        val service = cosmetics() ?: return
        val title = titleComponent(service, messages(), playerId)
        val color = nameColorOf(service, playerId)
        player.playerListName(
            Component.text().append(title).append(Component.text(player.name, color ?: NamedTextColor.WHITE)).build(),
        )
    }

    public companion object {
        /** `[Title] ` in gold, or empty when no title is equipped. */
        public suspend fun titleComponent(service: CosmeticService?, messages: IMessageRenderer?, playerId: PlayerId): Component {
            val title = service?.equipped(playerId, CosmeticKind.TITLE) ?: return Component.empty()
            val text = messages?.render(null, title.nameKey) ?: title.id
            return Component.text("[$text] ", NamedTextColor.GOLD)
        }

        /** The equipped name colour, or null when none. */
        public suspend fun nameColorOf(service: CosmeticService?, playerId: PlayerId): TextColor? =
            service?.equipped(playerId, CosmeticKind.NAME_COLOR)?.let { parseColor(it.value) }

        public fun parseColor(value: String): TextColor? =
            if (value.startsWith("#")) TextColor.fromHexString(value) else NamedTextColor.NAMES.value(value.lowercase())
    }
}
