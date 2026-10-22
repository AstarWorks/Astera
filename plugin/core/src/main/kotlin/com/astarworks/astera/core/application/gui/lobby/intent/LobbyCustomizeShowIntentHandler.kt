package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.CustomizeMenuGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — opens the customize sub-menu GUI in
 * response to the `lobby.customize.show` and `lobby.customize.open` intents.
 *
 * The Wave 5wireup [com.astarworks.astera.core.application.gui.lobby.MainMenuGuiSpec]
 * emits `lobby.customize.open` from its customize tile; the wave-5lobby-content
 * NPC YAML (`content/lobby-npcs/loadout-npc.yaml`) uses `lobby.customize.show`
 * for symmetry with the other show-the-sub-menu intents. Both keys land here
 * and open the same sub-menu — the user-facing distinction is purely the
 * upstream entry point (compass tile vs NPC).
 *
 * The sub-menu links to the personal vault, the profile card and settings
 * (see [CustomizeMenuGuiSpec]).
 */
public class LobbyCustomizeShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_SHOW, INTENT_OPEN -> openSubMenu(player)
            else -> log.warn(
                "Unknown lobby.customize intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
        }
    }

    private fun openSubMenu(player: IMcPlayer) {
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before customize sub-menu open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, CustomizeMenuGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.customize."
        public const val INTENT_SHOW: String = "lobby.customize.show"
        public const val INTENT_OPEN: String = "lobby.customize.open"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
