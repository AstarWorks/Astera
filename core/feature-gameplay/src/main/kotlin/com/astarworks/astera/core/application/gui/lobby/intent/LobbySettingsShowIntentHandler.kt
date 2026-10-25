package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.SettingsGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — opens the settings GUI in response to the
 * `lobby.settings.show` intent.
 *
 * Phase 1 PoC: the settings GUI is intentionally a skeleton —
 * locale / volume / control-scheme tiles are placeholder rows whose intent
 * handlers are scheduled for **Wave G** (`settings.locale.cycle`,
 * `settings.volume.adjust`, etc., each routing to a dedicated use case in
 * a subsequent wave). This wave only commits the GUI surface so the
 * hotbar / NPC route is end-to-end clickable today.
 */
public class LobbySettingsShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.settings intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before settings GUI open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, SettingsGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.settings."
        public const val INTENT_SHOW: String = "lobby.settings.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
