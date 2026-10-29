package com.astarworks.astera.chat.application

import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.config.ChatRank
import com.astarworks.astera.chat.application.port.outbound.IKanjiConverter
import com.astarworks.astera.chat.application.port.outbound.ITranslator
import com.astarworks.astera.chat.domain.Romaji
import com.astarworks.astera.core.application.port.outbound.IProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.proxy.ProxyChannel
import com.astarworks.astera.core.domain.proxy.ServerName
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlinx.serialization.json.Json

/** The same relay runs on Paper and in the two-backend simulator scenario. */
public class ChatRelay(
    private val server: ServerName,
    private val config: ChatConfig,
    private val messenger: IProxyMessenger,
    private val kanji: IKanjiConverter,
    private val display: (String, ChatFrame) -> Unit,
    private val discordOutbound: (String, ChatFrame) -> Unit = { _, _ -> },
    private val warn: (String) -> Unit = {},
    translator: ITranslator? = null,
    private val preferences: ChatPreferences = ChatPreferences(),
    private val playerNames: () -> Collection<String> = { emptyList() },
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val translation = ChatTranslation(config.translation, translator, warn)
    @Volatile private var active = true
    private val subscriptions = listOf(
        messenger.subscribe(ProxyChannel.CHAT) { message ->
            if (active && message.fromServer != server) {
                val frame = runCatching { json.decodeFromString<ChatFrame>(message.payload.decodeToString()) }.getOrNull()
                if (frame != null) deliver(message.fromServer.value, frame) else warn("Malformed chat frame dropped")
            }
        },
    )

    public fun toggle(player: UUID, enabled: Boolean): Result<Unit, ChatPreferenceError> = preferences.set(player, "japanize", enabled)
    public fun toggleTranslation(player: UUID, enabled: Boolean): Result<Unit, ChatPreferenceError> = preferences.set(player, "translate", enabled)
    public fun translationEnabled(player: UUID): Boolean = preferences.enabled(player, "translate", true)
    public fun forget(player: UUID) { preferences.forget(player) }

    public fun rank(roles: Set<String>): ChatRank = config.ranks
        .filter { rank -> rank.id == "member" || rank.roles.any { it in roles } }
        .maxBy { it.priority }

    public fun send(player: UUID, name: String, message: String, rank: ChatRank, nameColor: String? = null): CompletionStage<Void> {
        val names = playerNames() + name
        val kana = if (preferences.enabled(player, "japanize", config.japanize.enabled)) Romaji.hiragana(message, names) else null
        val converted = when {
            kana == null -> CompletableFuture.completedFuture(message)
            config.japanize.kanji -> kanji.convert(kana).exceptionally { kana }
            else -> CompletableFuture.completedFuture(kana)
        }
        return converted.thenCompose { text ->
            val shown = if (kana == null) text else "$text ($message)"
            translation.prepare(ChatFrame(name, shown, rank.prefix, rank.suffix, rank.decoration,
                playerId = player.toString(), nameColor = nameColor), text, names)
        }.thenAccept { frame -> if (active) publish(frame) }
    }

    public fun fromDiscord(name: String, message: String) {
        if (!active || message.isBlank()) return
        val original = message.take(2000)
        translation.prepare(ChatFrame(name.take(80), original, discord = true), original, playerNames() + name)
            .thenAccept { frame -> if (active) publish(frame) }
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

    override fun close() { active = false; subscriptions.forEach { it.cancel() } }
}
