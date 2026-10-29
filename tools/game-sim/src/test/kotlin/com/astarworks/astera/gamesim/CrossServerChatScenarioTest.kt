package com.astarworks.astera.gamesim

import com.astarworks.astera.chat.application.ChatRelay
import com.astarworks.astera.chat.application.chatCommands
import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.port.outbound.IKanjiConverter
import com.astarworks.astera.core.application.service.InMemoryProxyMessenger
import com.astarworks.astera.core.domain.proxy.ProxyMessage
import com.astarworks.astera.core.domain.proxy.ServerName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID

/** Production content + relay/codec + two backend messengers, with recorded network hops. No Paper or external HTTP. */
class CrossServerChatScenarioTest {
    @Test
    fun `two backends convert once relay without echo bridge Discord once`() {
        val shipped = ChatConfig.load(Path.of(System.getProperty("astera.sim.contentDir")).resolve("chat/chat.yaml"))
        val config = shipped.copy(japanize = shipped.japanize.copy(kanji = false))
        val lobbyName = ServerName("lobby")
        val gameName = ServerName("game")
        val lobbyBus = InMemoryProxyMessenger(lobbyName, setOf(gameName))
        val gameBus = InMemoryProxyMessenger(gameName, setOf(lobbyName))
        val lobbyLines = mutableListOf<Pair<String, ChatFrame>>()
        val gameLines = mutableListOf<Pair<String, ChatFrame>>()
        val discord = mutableListOf<ChatFrame>()
        val noHttp = IKanjiConverter { error("Kana-only configuration must not send text externally") }
        val lobby = ChatRelay(lobbyName, config, lobbyBus, noHttp,
            { origin, frame -> lobbyLines += origin to frame }, { _, frame -> discord += frame })
        val game = ChatRelay(gameName, config, gameBus, noHttp,
            { origin, frame -> gameLines += origin to frame })
        fun networkHop(from: InMemoryProxyMessenger, to: InMemoryProxyMessenger) {
            from.outboundRecorded().filter { it.targetServer == to.localServer }.forEach {
                to.publishInbound(ProxyMessage(from.localServer, it.channel, it.payload))
            }
            from.clearRecorded()
        }
        try {
            val player = UUID.randomUUID()
            val admin = game.rank(setOf("astera:admin", "moderator"))
            assertThat(admin.id).isEqualTo("admin")
            val member = game.rank(emptySet())
            assertThat(member.id).isEqualTo("member")
            assertThat(admin.decoration).isTrue()
            assertThat(game.rank(setOf("moderator")).decoration).isTrue()
            assertThat(member.decoration).isFalse()
            val samples = linkedMapOf(
                "konnichiwa" to "こんにちわ (konnichiwa)",
                "kanpai" to "かんぱい (kanpai)", "nn" to "ん (nn)", "gakkou" to "がっこう (gakkou)",
                "konnichiha" to "こんにちは (konnichiha)", "nya" to "にゃ (nya)", "n'ya" to "んや (n'ya)",
                "#konnichiwa" to "#konnichiwa", "こんにちは" to "こんにちは",
                "https://example.com" to "https://example.com", "/spawn" to "/spawn",
                "example.dev" to "example.dev", "EXAMPLE.TECH/path" to "EXAMPLE.TECH/path",
                "see example.museum/path" to "see example.museum/path",
                "<red>hi</red>" to "<red>ひ</red> (<red>hi</red>)",
                "konnnitiha red" to "こんにちは red (konnnitiha red)",
                "red" to "red", "HELLO red xyz" to "HELLO red xyz",
                "konnnitiha  RED!" to "こんにちは  RED! (konnnitiha  RED!)",
                "ko-hi-" to "こーひー (ko-hi-)", "n'" to "ん (n')",
                "&cX" to "&cX", "&#12abefX" to "&#12abefX",
            )
            samples.forEach { (input, output) ->
                game.send(player, "Alice", input, admin).toCompletableFuture().join()
                networkHop(gameBus, lobbyBus)
                assertThat(gameLines.last().second.message).isEqualTo(output)
                assertThat(lobbyLines.last()).isEqualTo("game" to gameLines.last().second)
            }
            assertThat(gameLines).hasSize(samples.size)
            assertThat(lobbyLines).hasSize(samples.size)
            assertThat(discord).hasSize(samples.size)
            assertThat(discord.first().prefix).contains("Admin")
            game.toggle(player, false)
            game.send(player, "Alice", "konnichiwa", admin).toCompletableFuture().join()
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines.last().second.message).isEqualTo("konnichiwa")
            assertThat(lobbyLines.last().second.color).isTrue()
            game.send(player, "Alice", "&cX {name} <click:run_command:/op>", member).toCompletableFuture().join()
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines.last().second.color).isFalse()
            assertThat(lobbyLines.last().second.message).isEqualTo("&cX {name} <click:run_command:/op>")
            // Highest rank's flag wins, even if a lower matching rank permits decoration.
            val restricted = config.copy(ranks = config.ranks.map { if (it.id == "admin") it.copy(decoration = false) else it })
            ChatRelay(gameName, restricted, gameBus, noHttp, { _, _ -> }).use { relay ->
                assertThat(relay.rank(setOf("admin", "mod")).decoration).isFalse()
            }
            game.forget(player)
            game.send(player, "Alice", "konnichiwa", admin).toCompletableFuture().join()
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines.last().second.message).isEqualTo("こんにちわ (konnichiwa)")
            val beforeDiscord = discord.size
            lobby.fromDiscord("Bob <red>", "hello <click:run_command:/op>")
            networkHop(lobbyBus, gameBus)
            assertThat(gameLines.last().second.discord).isTrue()
            assertThat(gameLines.last().second.message).isEqualTo("hello <click:run_command:/op>")
            assertThat(discord).hasSize(beforeDiscord)
            assertThat(chatCommands(game).literals.map { it.name }).containsExactly("japanize", "translate")
            lobby.close()
            game.send(player, "Alice", "bye", admin).toCompletableFuture().join()
            val count = lobbyLines.size
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines).hasSize(count)
        } finally { lobby.close(); game.close() }
    }
}
