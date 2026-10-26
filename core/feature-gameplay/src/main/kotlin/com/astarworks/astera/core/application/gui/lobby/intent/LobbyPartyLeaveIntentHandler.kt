package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.LeavePartyUseCase
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — leave your current party.
 *
 * Triggered by `lobby.party.leave`. [LeavePartyUseCase] handles every branch:
 * solo party → disband; leader-leaves multi → deterministic promotion;
 * member-leaves → simple removal. The GUI closes after a successful leave
 * so the next open shows the fresh state (or the "create party" entry tile).
 */
public class LobbyPartyLeaveIntentHandler(
    private val leaveParty: LeavePartyUseCase,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_LEAVE) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyPartyLeaveIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        when (val outcome = leaveParty.leave(player.id)) {
            is Result.Success -> {
                playerGateway.sendMessage(
                    player.id,
                    messages.render(player.id, MSG_LEFT, emptyMap()),
                )
                guiAdapter.close(player)
            }
            is Result.Failure -> {
                val err: PartyError = outcome.error
                playerGateway.sendMessage(
                    player.id,
                    messages.render(player.id, err.messageKey, emptyMap()),
                )
            }
        }
    }

    public companion object {
        public const val INTENT_LEAVE: String = "lobby.party.leave"

        private val MSG_LEFT = MessageKey("astera.party.leave.left")
    }
}
