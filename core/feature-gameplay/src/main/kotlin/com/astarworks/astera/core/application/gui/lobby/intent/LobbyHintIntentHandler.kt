package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * UI-04 (docs/game-design/access-map.md) - main-menu tiles for features whose only surface is a chat command
 * (Help): closes the menu and prints the commands to run, so the tile is a real
 * entry instead of a dead end. A feature that grows a GUI replaces its tile's intent with `gui.open`.
 *
 * Intent `lobby.hint.show`, payload `topic` = `help`; lines are `astera.hint.<topic>.line.<n>`.
 */
public class LobbyHintIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        val lines = TOPICS[intent.payload[PAYLOAD_TOPIC]] ?: run {
            log.warn("lobby.hint.show: unknown topic '{}' from {}", intent.payload[PAYLOAD_TOPIC], player.id)
            return
        }
        guiAdapter.close(player)
        for (key in lines) {
            playerGateway.sendMessage(player.id, messages.render(player.id, MessageKey(key), emptyMap()))
        }
    }

    public companion object {
        public const val INTENT_SHOW: String = "lobby.hint.show"
        public const val PAYLOAD_TOPIC: String = "topic"

        private val TOPICS: Map<String, List<String>> = mapOf(
            "help" to listOf(
                "astera.hint.help.header",
                "astera.hint.help.commands",
                "astera.hint.help.tutorial",
                "astera.hint.help.in-match",
            ),
        )
    }
}
