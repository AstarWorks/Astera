package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.AchievementViewerGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave M 5polish-quickwin (ADR-0130 §B) — opens the achievement viewer GUI in
 * response to the `lobby.achievement.show` intent.
 *
 * Same wiring shape as
 * [com.astarworks.astera.core.application.gui.lobby.intent.LobbyFriendsShowIntentHandler]
 * (Wave 5lobby-content): look up the player on the server, delegate to
 * `IGuiAdapter.open`, fall back to a chat error message if the open is
 * rejected.
 *
 * The actual achievement tile data flows through the
 * `IGuiBindingResolver` composite stack (Wave M §A) at render time — the
 * handler stays oblivious to stats / `IAchievementRepository`.
 */
public class LobbyAchievementShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.achievement intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before achievement viewer GUI open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, AchievementViewerGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.achievement."
        public const val INTENT_SHOW: String = "lobby.achievement.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
