package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.config.TitleFrame
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.adapter.paper.cosmetic.PaperCosmeticDisplay
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration

public class PaperChatDisplay(
    private val plugin: JavaPlugin,
    private val format: String,
    private val messages: IMessageRenderer,
) {
    private val mini = MiniMessage.miniMessage()
    private val legacy = LegacyComponentSerializer.legacyAmpersand()
    private val plain = PlainTextComponentSerializer.plainText()

    /** Placeholders are components: player MiniMessage tags are never interpreted. */
    public fun render(origin: String, frame: ChatFrame): Component = mini.deserialize(format,
        Placeholder.unparsed("server", origin),
        Placeholder.component("prefix", mini.deserialize(frame.prefix)),
        Placeholder.component("name", Component.text(frame.name, frame.nameColor?.let { TextColor.fromHexString(it) })),
        Placeholder.component("suffix", mini.deserialize(frame.suffix)),
        Placeholder.component("message", if (frame.color) legacy.deserialize(frame.message) else Component.text(frame.message)),
    )

    /** Preserve the previous Core formatter's equipped cosmetics and team name colour. */
    public fun appearance(player: PlayerId, matches: IMatchRepository?, cosmetics: CosmeticService?): Pair<String, String> {
        val team = matches?.findMatchOf(player)?.teams?.firstOrNull { player in it.members }
        val teamColor = team?.let { NamedTextColor.NAMES.value(messages.render(null, it.colorKey).lowercase()) }
        val color = teamColor ?: PaperCosmeticDisplay.nameColorOf(cosmetics, player) ?: NamedTextColor.GRAY
        val title = PaperCosmeticDisplay.titleComponent(cosmetics, messages, player)
        return mini.serialize(title) to color.asHexString()
    }

    public fun plainLine(origin: String, frame: ChatFrame): String = plain.serialize(render(origin, frame))

    public fun chat(origin: String, frame: ChatFrame) {
        if (!frame.discord) {
            val component = render(origin, frame)
            plugin.server.consoleSender.sendMessage(component)
            plugin.server.onlinePlayers.forEach { player ->
                player.scheduler.run(plugin, { player.sendMessage(component) }, null)
            }
        } else {
            plugin.server.onlinePlayers.forEach { player ->
                player.scheduler.run(plugin, {
                    // Interpolate after rendering the translation using component placeholders to avoid tag injection.
                    val template = messages.render(PlayerId(player.uniqueId), MessageKey("astera.chat.discord.line"))
                    player.sendMessage(mini.deserialize(template,
                        Placeholder.component("name", Component.text(frame.name, frame.nameColor?.let { TextColor.fromHexString(it) })), Placeholder.unparsed("message", frame.message)))
                }, null)
            }
        }
    }

    public fun title(frame: TitleFrame) {
        val title = Title.title(mini.deserialize(frame.title), mini.deserialize(frame.subtitle),
            Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000)))
        plugin.server.onlinePlayers.forEach { player -> player.scheduler.run(plugin, { player.showTitle(title) }, null) }
    }
}
