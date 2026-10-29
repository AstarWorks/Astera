package com.astarworks.astera.chat.adapter.paper

import com.astarworks.astera.chat.application.ChatPreferences
import com.astarworks.astera.chat.application.ChatTranslation
import com.astarworks.astera.chat.application.ChatView
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.core.adapter.paper.cosmetic.PaperCosmeticDisplay
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.plugin.java.JavaPlugin

public class PaperChatDisplay(
    private val plugin: JavaPlugin,
    private val format: String,
    private val messages: IMessageRenderer,
    private val marker: String,
    private val preferences: ChatPreferences,
    private val locale: (PlayerId) -> String,

) {
    private val legacy = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private val plain = PlainTextComponentSerializer.plainText()
    private val placeholders = "\\{(?:server|prefix|name|suffix|message)\\}"

    /** Replace once with components, so inserted player text cannot become format or placeholders. */
    private fun template(format: String, values: Map<String, Component>): Component =
        legacy.deserialize(format).replaceText { config ->
            config.match(placeholders).replacement { match, _ -> values.getValue(match.group()) }
        }

    public fun render(origin: String, frame: ChatFrame, body: Component = original(frame)): Component = template(format, mapOf(
        "{server}" to Component.text(origin),
        "{prefix}" to legacy.deserialize(frame.prefix),
        "{name}" to Component.text(frame.name, frame.nameColor?.let { TextColor.fromHexString(it) }),
        "{suffix}" to legacy.deserialize(frame.suffix),
        "{message}" to body,
    ))

    /** Preserve equipped cosmetics and team name colour; repository reads must run off-thread. */
    public fun appearance(player: PlayerId, matches: IMatchRepository?, cosmetics: CosmeticService?): Pair<String, String> {
        val team = matches?.findMatchOf(player)?.teams?.firstOrNull { player in it.members }
        val teamColor = team?.let { NamedTextColor.NAMES.value(messages.render(null, it.colorKey).lowercase()) }
        val color = teamColor ?: PaperCosmeticDisplay.nameColorOf(cosmetics, player) ?: NamedTextColor.GRAY
        val title = PaperCosmeticDisplay.titleComponent(cosmetics, messages, player)
        return legacy.serialize(title) to color.asHexString()
    }

    public fun plainLine(origin: String, frame: ChatFrame): String = plain.serialize(render(origin, frame))

    private fun original(frame: ChatFrame): Component =
        if (frame.color) legacy.deserialize(frame.message) else Component.text(frame.message)

    private fun body(frame: ChatFrame, viewer: PlayerId): Component {
        val shown = ChatView.message(frame, locale(viewer), preferences.enabled(viewer.uuid, "translate", true), marker)
        if (shown.marker == null) return original(frame)
        val translated = shown.text
        // Only sender-supplied leading decoration may style a translation; model output is always literal.
        val leading = if (frame.color) ChatTranslation.decoration.findAll(frame.message)
            .takeWhile { it.range.first == 0 || frame.message.substring(0, it.range.first)
                .let { prefix -> ChatTranslation.decoration.replace(prefix, "").isEmpty() } }
            .joinToString("") { it.value } else ""
        val text = if (leading.isEmpty()) Component.text(translated)
            else Component.text(translated).style(legacy.deserialize(leading + "x").style())
        return Component.empty().append(legacy.deserialize(shown.marker)).append(Component.text(" ")).append(text)
            .hoverEvent(Component.text(shown.hover ?: ""))
    }

    public fun chat(origin: String, frame: ChatFrame) {
        if (!frame.discord) plugin.server.consoleSender.sendMessage(render(origin, frame))
        // Repository and file reads run on the async scheduler, never on an entity/region thread.
        plugin.server.asyncScheduler.runNow(plugin) {
            plugin.server.onlinePlayers.forEach { player ->
                val id = PlayerId(player.uniqueId)
                val body = body(frame, id)
                val component = if (!frame.discord) render(origin, frame, body) else {
                    val pattern = messages.render(id, MessageKey("astera.chat.discord.line"))
                    template(pattern, mapOf("{name}" to Component.text(frame.name), "{message}" to body))
                }
                player.scheduler.run(plugin, { player.sendMessage(component) }, null)
            }
        }
    }
}
