package com.astarworks.astera.chat.application

import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.config.ChatRank
import com.astarworks.astera.chat.application.port.outbound.IKanjiConverter
import com.astarworks.astera.chat.domain.Romaji
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** The same relay runs on Paper and in the two-backend simulator scenario. */
public class ChatRelay(
    private val server: ServerName,
    private val config: ChatConfig,
    private val messenger: IProxyMessenger,
    private val kanji: IKanjiConverter,
    private val display: (String, ChatFrame) -> Unit,
    private val discordOutbound: (String, ChatFrame) -> Unit = { _, _ -> },
    private val warn: (String) -> Unit = {},
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val toggles = ConcurrentHashMap<UUID, Boolean>()
    @Volatile private var active = true
    private val subscriptions = listOf(
        messenger.subscribe(ProxyChannel.CHAT) { message ->
            if (active && message.fromServer != server) {
                val frame = runCatching { json.decodeFromString<ChatFrame>(message.payload.decodeToString()) }.getOrNull()
                if (frame != null) deliver(message.fromServer.value, frame) else warn("Malformed chat frame dropped")
            }
        },
    )

    public fun toggle(player: UUID, enabled: Boolean) { toggles[player] = enabled }
    public fun forget(player: UUID) { toggles.remove(player) }

    public fun rank(roles: Set<String>): ChatRank = config.ranks
        .filter { rank -> rank.id == "member" || rank.roles.any { it in roles } }
        .maxBy { it.priority }

    public fun send(player: UUID, name: String, message: String, rank: ChatRank, nameColor: String? = null): CompletionStage<Void> {
        val kana = if (toggles[player] ?: config.japanize.enabled) Romaji.hiragana(message) else null
        val converted = when {
            kana == null -> CompletableFuture.completedFuture(message)
            config.japanize.kanji -> kanji.convert(kana).exceptionally { kana }.thenApply { "$it ($message)" }
            else -> CompletableFuture.completedFuture("$kana ($message)")
        }
        return converted.thenAccept { text ->
            if (active) publish(ChatFrame(name, text, rank.prefix, rank.suffix, rank.decoration, playerId = player.toString(), nameColor = nameColor))
        }
    }

    public fun fromDiscord(name: String, message: String) {
        if (active && message.isNotBlank()) publish(ChatFrame(name.take(80), message.take(2000), discord = true))
    }

    private fun publish(frame: ChatFrame) {
        deliver(server.value, frame)
        if (messenger.broadcast(ProxyChannel.CHAT, json.encodeToString(ChatFrame.serializer(), frame).encodeToByteArray()) is Result.Failure)
            warn("Chat could not be relayed to other servers")
    }

    private fun deliver(origin: String, frame: ChatFrame) {
        display(origin, frame)
        if (!frame.discord) discordOutbound(origin, frame)
    }

    override fun close() { active = false; subscriptions.forEach { it.cancel() }; toggles.clear() }
}
