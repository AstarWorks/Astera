package com.astarworks.astera.gamesim

import com.astarworks.astera.chat.application.ChatRelay
import com.astarworks.astera.chat.application.chatCommands
import com.astarworks.astera.chat.application.config.ChatConfig
import com.astarworks.astera.chat.application.config.ChatFrame
import com.astarworks.astera.chat.application.config.TitleFrame
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
    fun `two backends convert once relay without echo bridge Discord once and route titles`() {
        val shipped = ChatConfig.load(Path.of(System.getProperty("astera.sim.contentDir")).resolve("chat/chat.yaml"))
        val config = shipped.copy(japanize = shipped.japanize.copy(kanji = false))
        val lobbyName = ServerName("lobby")
        val gameName = ServerName("game")
        val lobbyBus = InMemoryProxyMessenger(lobbyName, setOf(gameName))
        val gameBus = InMemoryProxyMessenger(gameName, setOf(lobbyName))
        val lobbyLines = mutableListOf<Pair<String, ChatFrame>>()
        val gameLines = mutableListOf<Pair<String, ChatFrame>>()
        val discord = mutableListOf<ChatFrame>()
        val lobbyTitles = mutableListOf<TitleFrame>()
        val gameTitles = mutableListOf<TitleFrame>()
        val noHttp = IKanjiConverter { error("Kana-only configuration must not send text externally") }
        val lobby = ChatRelay(lobbyName, config, lobbyBus, noHttp,
            { origin, frame -> lobbyLines += origin to frame }, { lobbyTitles += it }, { _, frame -> discord += frame })
        val game = ChatRelay(gameName, config, gameBus, noHttp,
            { origin, frame -> gameLines += origin to frame }, { gameTitles += it })
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
            assertThat(game.rank(emptySet()).id).isEqualTo("member")
            val samples = linkedMapOf(
                "konnichiwa" to "こんにちわ (konnichiwa)",
                "kanpai" to "かんぱい (kanpai)", "nn" to "ん (nn)", "gakkou" to "がっこう (gakkou)",
                "konnichiha" to "こんにちは (konnichiha)", "nya" to "にゃ (nya)", "n'ya" to "んや (n'ya)",
                "#konnichiwa" to "#konnichiwa", "こんにちは" to "こんにちは",
                "https://example.com" to "https://example.com", "/spawn" to "/spawn",
                "example.dev" to "example.dev", "EXAMPLE.TECH/path" to "EXAMPLE.TECH/path",
                "see example.museum/path" to "see example.museum/path",
                "<red>hi</red>" to "<れd>ひ</れd> (<red>hi</red>)",
            )
            samples.forEach { (input, output) ->
                game.send(player, "Alice", input, admin, false).toCompletableFuture().join()
                networkHop(gameBus, lobbyBus)
                assertThat(gameLines.last().second.message).isEqualTo(output)
                assertThat(lobbyLines.last()).isEqualTo("game" to gameLines.last().second)
            }
            assertThat(gameLines).hasSize(samples.size)
            assertThat(lobbyLines).hasSize(samples.size)
            assertThat(discord).hasSize(samples.size)
            assertThat(discord.first().prefix).contains("Admin")
            game.toggle(player, false)
            game.send(player, "Alice", "konnichiwa", admin, true).toCompletableFuture().join()
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines.last().second.message).isEqualTo("konnichiwa")
            assertThat(lobbyLines.last().second.color).isTrue()
            game.forget(player)
            game.send(player, "Alice", "konnichiwa", admin, false).toCompletableFuture().join()
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines.last().second.message).isEqualTo("こんにちわ (konnichiwa)")
            val beforeDiscord = discord.size
            lobby.fromDiscord("Bob <red>", "hello <click:run_command:/op>")
            networkHop(lobbyBus, gameBus)
            assertThat(gameLines.last().second.discord).isTrue()
            assertThat(gameLines.last().second.message).isEqualTo("hello <click:run_command:/op>")
            assertThat(discord).hasSize(beforeDiscord)
            game.title("lobby", "<gold>Hi", "Sub")
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyTitles).hasSize(1)
            assertThat(gameTitles).isEmpty()
            lobby.title("all", "Hello", "World")
            networkHop(lobbyBus, gameBus)
            assertThat(lobbyTitles).hasSize(2)
            assertThat(gameTitles).hasSize(1)
            assertThat(chatCommands(game).literals.map { it.name }).containsExactly("japanize", "title")
            lobby.close()
            game.send(player, "Alice", "bye", admin, false).toCompletableFuture().join()
            val count = lobbyLines.size
            networkHop(gameBus, lobbyBus)
            assertThat(lobbyLines).hasSize(count)
        } finally { lobby.close(); game.close() }
    }
}
