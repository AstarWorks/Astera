package com.astarworks.astera.gamesim

import com.astarworks.astera.chat.adapter.paper.FileChatPreferences
import com.astarworks.astera.chat.adapter.paper.HttpTranslator
import com.astarworks.astera.chat.application.ChatPreferences
import com.astarworks.astera.chat.application.ChatRelay
import com.astarworks.astera.chat.application.ChatView
import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.port.outbound.IKanjiConverter
import com.astarworks.astera.chat.application.port.outbound.ITranslator
import com.astarworks.astera.chat.application.port.outbound.TranslateError
import com.astarworks.astera.core.application.service.InMemoryProxyMessenger
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Shipped content + real relay, codec, viewer choice and durable preferences across two backends. */
class ChatTranslationScenarioTest {
    @TempDir lateinit var directory: Path

    private fun config(): ChatConfig = ChatConfig.load(Path.of(System.getProperty("astera.sim.contentDir"))
        .resolve("chat/chat.yaml")).let { it.copy(japanize = it.japanize.copy(kanji = false)) }
    private val noKanji = IKanjiConverter { error("Kana-only scenario must not call Google") }

    private class Network {
        val lobby = InMemoryProxyMessenger(ServerName("lobby"), setOf(ServerName("game")))
        val game = InMemoryProxyMessenger(ServerName("game"), setOf(ServerName("lobby")))
        fun hop() {
            lobby.outboundRecorded().filter { it.targetServer == game.localServer }.forEach { game.publishInbound(ProxyMessage(lobby.localServer, it.channel, it.payload)) }
            lobby.clearRecorded()
        }
    }

    @Test
    fun `Japanize then translate once on sender and display by viewer locale with marker and original hover`() {
        val config = config()
        val network = Network()
        val sender = UUID.randomUUID()
        val english = UUID.randomUUID()
        val japanese = UUID.randomUUID()
        val calls = mutableListOf<Triple<String, String, String>>()
        val frames = mutableListOf<ChatFrame>()
        val views = mutableMapOf<UUID, ChatView.Message>()
        val discord = mutableListOf<ChatFrame>()
        val prefs = ChatPreferences()
        val translator = ITranslator { text, from, to ->
            calls += Triple(text, from, to)
            CompletableFuture.completedFuture(Result.Success("Hello"))
        }
        ChatRelay(network.game.localServer, config, network.game, noKanji, { _, frame ->
            frames += frame
            views[english] = ChatView.message(frame, "en-US", prefs.enabled(english, "translate", true), config.translation.marker)
            views[japanese] = ChatView.message(frame, "ja", true, config.translation.marker)
        }, translator = ITranslator { _, _, _ -> error("Receiver must never translate") }, preferences = prefs).use { receiver ->
            ChatRelay(network.lobby.localServer, config, network.lobby, noKanji, { _, _ -> },
                discordOutbound = { _, frame -> discord += frame }, translator = translator).use { lobby ->
                lobby.send(sender, "Alice", "konnichiha", lobby.rank(emptySet())).toCompletableFuture().join()
                network.hop()
                assertThat(calls).containsExactly(Triple("こんにちは", "ja", "en"))
                assertThat(views.getValue(english)).isEqualTo(ChatView.Message("Hello", config.translation.marker, "[ja] こんにちは (konnichiha)"))
                assertThat(views.getValue(japanese)).isEqualTo(ChatView.Message("こんにちは (konnichiha)"))
                assertThat(frames.single().translations).containsExactlyEntriesOf(mapOf("en" to "Hello"))
                assertThat(discord.single().message).isEqualTo("こんにちは (konnichiha)")
                receiver.toggleTranslation(english, false)
                lobby.send(sender, "Alice", "こんにちは", lobby.rank(emptySet())).toCompletableFuture().join()
                network.hop()
                assertThat(views.getValue(english)).isEqualTo(ChatView.Message("こんにちは"))
                assertThat(frames).hasSize(2) // No local echo / receiver fan-out.
            }
        }
    }

    @Test
    fun `total timeout sends original exactly once and late model answer cannot publish again`() {
        val config = config().let { it.copy(translation = it.translation.copy(timeoutMs = 40)) }
        val network = Network()
        val frames = CopyOnWriteArrayList<ChatFrame>()
        val warnings = CopyOnWriteArrayList<String>()
        val pending = CompletableFuture<Result<String, TranslateError>>()
        ChatRelay(network.game.localServer, config, network.game, noKanji, { _, frame -> frames += frame }).use {
            ChatRelay(network.lobby.localServer, config, network.lobby, noKanji, { _, _ -> },
                warn = warnings::add, translator = ITranslator { _, _, _ -> pending }).use { lobby ->
                val delivered = lobby.send(UUID.randomUUID(), "Alice", "助けて", lobby.rank(emptySet()))
                delivered.toCompletableFuture().get(2, TimeUnit.SECONDS)
                network.hop()
                assertThat(frames.single().message).isEqualTo("助けて")
                assertThat(frames.single().translations).isEmpty()
                assertThat(ChatView.message(frames.single(), "en", true, config.translation.marker).marker).isNull()
                assertThat(warnings).hasSize(1)
                pending.complete(Result.Success("Help"))
                network.hop()
                assertThat(frames).hasSize(1)
            }
        }
    }

    @Test
    fun `Discord shares translation protection and original is not bridged back`() {
        val config = config().let { it.copy(translation = it.translation.copy(languages = listOf("en", "ja", "ko", "es"))) }
        val network = Network()
        val frames = mutableListOf<ChatFrame>()
        val inputs = mutableListOf<String>()
        val targets = mutableListOf<String>()
        val translator = ITranslator { text, _, to ->
            inputs += text; targets += to
            CompletableFuture.completedFuture(Result.Success(text.replace("助けて", "Help")))
        }
        ChatRelay(network.game.localServer, config, network.game, noKanji, { _, frame -> frames += frame }).use {
            ChatRelay(network.lobby.localServer, config, network.lobby, noKanji, { _, _ -> },
                discordOutbound = { _, _ -> error("Discord input must not echo to Discord") },
                translator = translator, playerNames = { listOf("Steve") }).use { lobby ->
                lobby.fromDiscord("Alice", "&cSteve 助けて https://example.com/?x=1&b=2 😀")
                network.hop()
                val frame = frames.single()
                assertThat(frame.discord).isTrue()
                assertThat(frame.sourceLanguage).isEqualTo("ja")
                assertThat(targets).containsExactly("en", "ko", "es")
                assertThat(inputs).allSatisfy { input ->
                    assertThat(input).doesNotContain("&c", "Steve", "https://").contains("__ASTERA_0__", "__ASTERA_1__")
                }
                assertThat(frame.translations.getValue("en")).isEqualTo("Steve Help https://example.com/?x=1&b=2 😀")
            }
        }
    }

    @Test
    fun `translation disabled without URL and broken placeholder response always deliver original`() {
        val config = config()
        val network = Network()
        val frames = mutableListOf<ChatFrame>()
        ChatRelay(network.lobby.localServer, config, network.lobby, noKanji, { _, frame -> frames += frame }).use { lobby ->
            lobby.send(UUID.randomUUID(), "Alice", "助けて", lobby.rank(emptySet())).toCompletableFuture().join()
            assertThat(frames.single().translations).isEmpty()
        }
        frames.clear()
        ChatRelay(network.lobby.localServer, config, network.lobby, noKanji, { _, frame -> frames += frame },
            translator = ITranslator { _, _, _ -> CompletableFuture.completedFuture(Result.Success("Name was changed")) },
            playerNames = { listOf("Sora") }).use { lobby ->
            lobby.send(UUID.randomUUID(), "Alice", "Sora 助けて", lobby.rank(emptySet())).toCompletableFuture().join()
            assertThat(frames.single().translations).isEmpty()
            assertThat(frames.single().message).isEqualTo("Sora 助けて")
            lobby.send(UUID.randomUUID(), "Alice", "Sora konnichiha", lobby.rank(emptySet())).toCompletableFuture().join()
            assertThat(frames.last().message).isEqualTo("Sora konnichiha")
        }
    }

    @Test
    fun `both preferences persist through reconnect restart and another backend`() {
        val player = UUID.randomUUID()
        val firstStore = FileChatPreferences(directory) { error(it) }
        val first = ChatPreferences(firstStore::read, firstStore::write)
        first.set(player, "translate", false)
        first.set(player, "japanize", false)
        first.forget(player)
        val secondStore = FileChatPreferences(directory) { error(it) }
        val second = ChatPreferences(secondStore::read, secondStore::write)
        val config = config()
        val frames = mutableListOf<ChatFrame>()
        val network = Network()
        ChatRelay(network.game.localServer, config, network.game, noKanji, { _, frame -> frames += frame }, preferences = second).use { game ->
            assertThat(game.translationEnabled(player)).isFalse()
            game.send(player, "Alice", "konnichiha", game.rank(emptySet())).toCompletableFuture().join()
            assertThat(frames.single().message).isEqualTo("konnichiha")
            game.toggleTranslation(player, true)
            assertThat(first.enabled(player, "translate", false)).isTrue()
            assertThat(first.enabled(player, "japanize", true)).isFalse()
        }
    }

    @Test
    fun `real HTTP adapter profiles and cache participate in sender relay`() {
        val requests = CopyOnWriteArrayList<String>()
        val stub = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        stub.createContext("/v1/chat/completions") { exchange ->
            requests += exchange.requestBody.readAllBytes().decodeToString()
            val bytes = """{"choices":[{"finish_reason":"stop","message":{"content":"Hello"}}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        stub.start()
        try {
            HttpClient.newHttpClient().use { http ->
                for (profile in listOf("translategemma", "hy-mt", "lfm2")) {
                    val config = config().let { it.copy(translation = it.translation.copy(modelProfile = profile)) }
                    val adapter = HttpTranslator(http, "http://127.0.0.1:${stub.address.port}", profile, 1500)
                    val network = Network()
                    val frames = mutableListOf<ChatFrame>()
                    ChatRelay(network.lobby.localServer, config, network.lobby, noKanji,
                        { _, frame -> frames += frame }, translator = adapter).use { lobby ->
                        repeat(2) { lobby.send(UUID.randomUUID(), "Alice", "こんにちは", lobby.rank(emptySet())).toCompletableFuture().join() }
                        assertThat(frames).hasSize(2).allSatisfy { frame -> assertThat(frame.translations).containsEntry("en", "Hello") }
                    }
                }
                assertThat(requests).hasSize(3)
                assertThat(requests[0]).contains("professional Japanese (ja) to English (en) translator")
                assertThat(requests[1]).contains("Translate the following segment into English")
                assertThat(requests[2]).contains("Translate to English.", "system")
            }
        } finally { stub.stop(0) }
    }
}
