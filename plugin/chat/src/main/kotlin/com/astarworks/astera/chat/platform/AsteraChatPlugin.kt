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
import com.astarworks.astera.core.application.port.inbound.IAsteraCommandRegistry
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPermissionService
import com.astarworks.astera.core.application.port.outbound.IPlayerSettingsRepository
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.application.services.ServiceRegistry
import com.astarworks.astera.core.application.services.get
import com.astarworks.astera.core.application.services.getOrThrow
import com.astarworks.astera.core.platform.AsteraCorePlugin
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import org.bukkit.plugin.java.JavaPlugin

public class AsteraChatPlugin : JavaPlugin() {
    private var relay: ChatRelay? = null
    @Volatile private var discord: DiscordBridge? = null
    private var commands: ServiceRegistry.Registration? = null
    private var http: HttpClient? = null

    override fun onEnable() {
        val core = server.pluginManager.getPlugin("Astera-Core") as? AsteraCorePlugin
            ?: error("Astera-Core is required")
        val config = ChatConfig.load(core.contentDir.resolve("chat/chat.yaml"))
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        http = client
        val storage = FileChatPreferences(Path.of(System.getenv("ASTERA_CHAT_PREFERENCES_DIR")
            ?: dataFolder.resolve("preferences").path), logger::warning)
        val preferences = ChatPreferences(storage::read, storage::write)
        val url = System.getenv("ASTERA_TRANSLATE_URL")?.takeIf { it.isNotBlank() }
        val translator = if (config.translation.enabled && url != null)
            HttpTranslator(client, url, config.translation) else null
        if (translator == null) logger.info("Chat translation disabled (requires translation.enabled and ASTERA_TRANSLATE_URL)")
        val display = PaperChatDisplay(this, config.format, core.services.getOrThrow<IMessageRenderer>(),
            config.translation.marker, preferences,
            locale = { id -> core.services.get<IPlayerSettingsRepository>()?.find(id)?.locale?.code
                ?: core.serverEnvironment.defaultLocale.code })
        val chat = ChatRelay(core.serverEnvironment.name, config, core.services.getOrThrow<IProxyMessenger>(),
            GoogleKanjiConverter(client), display::chat,
            discordOutbound = { origin, frame -> discord?.send(origin, frame) }, warn = { logger.warning(it) },
            translator = translator, preferences = preferences,
            playerNames = { server.onlinePlayers.map { it.name } })
        relay = chat
        commands = core.services.getOrThrow<IAsteraCommandRegistry>().register(name, chatCommands(chat))
        val matches = core.services.get<com.astarworks.astera.core.application.port.outbound.IMatchRepository>()
        val cosmetics = core.services.get<com.astarworks.astera.core.application.service.cosmetic.CosmeticService>()
        server.pluginManager.registerEvents(PaperChatListener(this, chat, core.services.getOrThrow<IPermissionService>(),
            appearance = { id -> display.appearance(id, matches, cosmetics) }), this)
        val token = System.getenv("ASTERA_DISCORD_BOT_TOKEN")?.takeIf { it.isNotBlank() }
        val channel = System.getenv("ASTERA_DISCORD_CHANNEL_ID")?.takeIf { it.matches(Regex("[0-9]+")) }
        if (config.discord.enabled && config.discord.bridgeServer == core.serverEnvironment.name.value && token != null && channel != null) {
            val webhook = System.getenv("ASTERA_CHAT_DISCORD_WEBHOOK_URL")?.takeIf { it.startsWith("https://discord.com/api/webhooks/") }
            discord = DiscordBridge(client, token, channel, webhook, display::plainLine, chat::fromDiscord, logger).also { it.start() }
        } else logger.info("Discord chat disabled on this backend (requires bridge-server, ASTERA_DISCORD_BOT_TOKEN and ASTERA_DISCORD_CHANNEL_ID)")
        logger.info("Astera-Chat enabled")
    }

    override fun onDisable() {
        commands?.unregister(); commands = null
        relay?.close(); relay = null
        discord?.close(); discord = null
        http?.shutdownNow(); http = null
        server.asyncScheduler.cancelTasks(this)
    }
}
