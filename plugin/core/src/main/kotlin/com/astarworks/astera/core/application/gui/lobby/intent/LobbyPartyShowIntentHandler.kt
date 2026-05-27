package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.PartyManagerGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — opens the party manager GUI in response to
 * the `lobby.party.show` intent.
 *
 * Wave 5e shipped the Party domain + use cases
 * ([com.astarworks.astera.core.application.usecase.social.CreatePartyUseCase],
 * [com.astarworks.astera.core.application.usecase.social.JoinPartyUseCase],
 * [com.astarworks.astera.core.application.usecase.social.LeavePartyUseCase],
 * [com.astarworks.astera.core.application.usecase.social.DisbandPartyUseCase]);
 * this wave wires the **GUI surface** that lets a player open them from the
 * hotbar. Member listing pulls live data through `DefaultGuiBindingResolver`
 * once `player.party.*` bindings land in a future wave. Phase 1 PoC keeps
 * the spec skeletal — close button + placeholder tiles.
 */
public class LobbyPartyShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.party intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before party manager open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, PartyManagerGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.party."
        public const val INTENT_SHOW: String = "lobby.party.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
