package com.astarworks.astera.core.application.eventbus.listeners.onboarding

import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.event.PlayerJoinedServer
import com.astarworks.astera.core.domain.i18n.LocalizedLore
import com.astarworks.astera.core.domain.i18n.LocalizedText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class HintOnPlayerJoinedListenerTest {

    private val renderer = RecordingRenderer()
    private val playerGw = RecordingPlayerGateway()
    private val listener = HintOnPlayerJoinedListener(playerGw, renderer)

    @Test
    fun `handle sends one tip per join in round-robin order`() {
        repeat(8) {
            listener.handle(PlayerJoinedServer(PlayerId(UUID.randomUUID())))
        }

        // 8 joins × 6-tip catalogue → 1,2,3,4,5,6,1,2 rotation
        assertThat(playerGw.sent).hasSize(8)
        val sentTipKeys = renderer.rendered.map { it.second.value }
        assertThat(sentTipKeys).containsExactly(
            "astera.tip.1",
            "astera.tip.2",
            "astera.tip.3",
            "astera.tip.4",
            "astera.tip.5",
            "astera.tip.6",
            "astera.tip.1",
            "astera.tip.2",
        )
    }

    @Test
    fun `handle swallows renderer exceptions`() {
        val throwingRenderer = object : IMessageRenderer {
            override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String =
                throw RuntimeException("translation backend offline")
            override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> =
                throw UnsupportedOperationException("not used")
            override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String =
                throw UnsupportedOperationException("not used")
            override fun renderLocalizedLore(playerId: PlayerId?, lore: LocalizedLore, placeholders: Map<String, String>): List<String> =
                throw UnsupportedOperationException("not used")
        }
        val resilientListener = HintOnPlayerJoinedListener(playerGw, throwingRenderer)
        resilientListener.handle(PlayerJoinedServer(PlayerId(UUID.randomUUID())))
        // Should not throw; nothing sent because render failed
        assertThat(playerGw.sent).isEmpty()
    }

    private class RecordingRenderer : IMessageRenderer {
        val rendered: MutableList<Pair<PlayerId?, MessageKey>> = mutableListOf()
        override fun render(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): String {
            rendered += playerId to key
            return "tip:${key.value}"
        }
        override fun renderLore(playerId: PlayerId?, key: MessageKey, placeholders: Map<String, String>): List<String> =
            listOf(render(playerId, key, placeholders))
        override fun renderText(playerId: PlayerId?, text: LocalizedText, placeholders: Map<String, String>): String =
            "text-stub"
        override fun renderLocalizedLore(
            playerId: PlayerId?,
            lore: LocalizedLore,
            placeholders: Map<String, String>,
        ): List<String> = emptyList()
    }

    private class RecordingPlayerGateway : IPlayerGateway {
        val sent: MutableList<Pair<PlayerId, String>> = mutableListOf()
        override fun findByName(name: String): PlayerId? = null
        override fun sendMessage(playerId: PlayerId, rendered: String) {
            sent += playerId to rendered
        }
    }
}
