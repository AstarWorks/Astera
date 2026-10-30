package com.astarworks.astera.chat.platform

import com.astarworks.astera.chat.adapter.paper.DiscordBridge
import com.astarworks.astera.chat.adapter.paper.FileChatPreferences
import com.astarworks.astera.chat.adapter.paper.GoogleKanjiConverter
import com.astarworks.astera.chat.adapter.paper.HttpTranslator
import com.astarworks.astera.chat.adapter.paper.PaperChatDisplay
import com.astarworks.astera.chat.adapter.paper.PaperChatListener
import com.astarworks.astera.chat.application.ChatPreferences
import com.astarworks.astera.chat.application.ChatRelay
import com.astarworks.astera.chat.application.chatCommands
import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.service.cosmetic.CosmeticService
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.application.wiring.WiringOwner
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.platform.AsteraCorePlugin
import com.astarworks.astera.core.platform.paper.listeners
import com.astarworks.astera.core.platform.wiring.LifecycleFailure
import com.astarworks.astera.core.platform.wiring.PluginLifecycle
import com.astarworks.astera.core.platform.wiring.WiringContext
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import org.bukkit.plugin.java.JavaPlugin

/** What [ChatLifecycle.wire] built and [ChatLifecycle.bind] needs: the relay plus the Discord bridge factory. */
public class ChatWired internal constructor(
    internal val relay: ChatRelay,
    private val startDiscord: () -> DiscordBridge?,
    private val setDiscord: (DiscordBridge?) -> Unit,
) {
    private var discord: DiscordBridge? = null

    internal fun bind() {
        discord = startDiscord()
        setDiscord(discord)
    }

    internal fun unbind() {
        setDiscord(null)
        discord?.close()
        discord = null
    }
}

/**
 * Chat as a rewirable plugin (ADR-0311). [wire] builds the relay, display, `/astera chat` commands and the Bukkit
 * listener (preferences are carried over via `WiringContext.stateful`); [bind] connects the Discord bridge, the only
 * external connection, and [unbind] drops it. The HTTP client and the relay are released when the generation closes.
 */
public class ChatLifecycle(
    private val plugin: JavaPlugin,
    private val core: AsteraCorePlugin,
) : PluginLifecycle<ChatWired> {

    override val owner: WiringOwner = OWNER
    override val dependsOn: List<WiringOwner> = emptyList()

    override fun wire(ctx: WiringContext): Result<ChatWired, LifecycleFailure> =
        try {
            Result.Success(build(ctx))
        } catch (e: Exception) {
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    private fun build(ctx: WiringContext): ChatWired {
        val logger = ctx.logger
        val services = ctx.services
        val config = ChatConfig.load(ctx.contentDir.resolve("chat/chat.yaml"))
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        ctx.onClose { client.shutdownNow() }
        val preferences = ctx.stateful<ChatPreferences> {
            val storage = FileChatPreferences(
                Path.of(System.getenv("ASTERA_CHAT_PREFERENCES_DIR") ?: plugin.dataFolder.resolve("preferences").path),
                logger::warning,
            )
            ChatPreferences(storage::read, storage::write)
        }
        val url = System.getenv("ASTERA_TRANSLATE_URL")?.takeIf { it.isNotBlank() }
        val translator = if (config.translation.enabled && url != null) HttpTranslator(client, url, config.translation) else null
        if (translator == null) logger.info("Chat translation disabled (requires translation.enabled and ASTERA_TRANSLATE_URL)")
        val display = PaperChatDisplay(
            plugin, config.format, services.getOrThrow<IMessageRenderer>(), config.translation.marker, preferences,
            locale = { id -> services.get<IPlayerSettingsRepository>()?.find(id)?.locale?.code ?: core.serverEnvironment.defaultLocale.code },
        )
        var discord: DiscordBridge? = null
        val chat = ChatRelay(
            ctx.serverName, config, services.getOrThrow<IProxyMessenger>(),
            GoogleKanjiConverter(client), display::chat,
            discordOutbound = { origin, frame -> discord?.send(origin, frame) }, warn = { logger.warning(it) },
            translator = translator, preferences = preferences,
            playerNames = { plugin.server.onlinePlayers.map { it.name } },
        )
        ctx.onClose { chat.close() }
        ctx.commandMount().register(chatCommands(chat))
        val matches = services.get<IMatchRepository>()
        val cosmetics = services.get<CosmeticService>()
        ctx.listeners(plugin).register(
            PaperChatListener(
                plugin, chat, services.getOrThrow<IPermissionService>(),
                appearance = { id -> display.appearance(id, matches, cosmetics) },
            ),
        )
        val startDiscord: () -> DiscordBridge? = {
            val token = System.getenv("ASTERA_DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() }
            val channel = System.getenv("ASTERA_DISCORD_CHANNEL_ID")?.takeIf { it.matches(Regex("[0-9]+")) }
            if (config.discord.enabled && config.discord.bridgeServer == ctx.serverName.value && token != null && channel != null) {
                val webhook = System.getenv("ASTERA_CHAT_DISCORD_WEBHOOK_URL")?.takeIf { it.startsWith("https://discord.com/api/webhooks/") }
                DiscordBridge(client, token, channel, webhook, display::plainLine, chat::fromDiscord, logger).also { it.start() }
            } else {
                logger.info("Discord chat disabled on this backend (requires bridge-server, ASTERA_DISCORD_BOT_TOKEN and ASTERA_DISCORD_CHANNEL_ID)")
                null
            }
        }
        return ChatWired(chat, startDiscord) { discord = it }
    }

    override fun bind(ctx: WiringContext, wired: ChatWired): Result<Unit, LifecycleFailure> =
        try {
            wired.bind()
            Result.Success(Unit)
        } catch (e: Exception) {
            wired.unbind()
            Result.Failure(LifecycleFailure("${e::class.simpleName}: ${e.message}"))
        }

    override fun unbind(ctx: WiringContext, wired: ChatWired) {
        wired.unbind()
    }

    public companion object {
        public val OWNER: WiringOwner = WiringOwner("Astera-Chat")
    }
}
