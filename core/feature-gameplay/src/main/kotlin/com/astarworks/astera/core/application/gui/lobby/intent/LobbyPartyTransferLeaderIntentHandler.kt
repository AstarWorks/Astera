package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.application.usecase.social.TransferPartyLeaderUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — transfer party leadership.
 *
 * Triggered by `lobby.party.transfer-leader`. Leader-only — the use case
 * [TransferPartyLeaderUseCase] returns [PartyError.NotLeader] for non-leader
 * callers and the no-op same-person case alike.
 *
 * `payload[target]` is the new leader's name. Missing payload → command path.
 */
public class LobbyPartyTransferLeaderIntentHandler(
    private val transferLeader: TransferPartyLeaderUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_TRANSFER) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyPartyTransferLeaderIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val targetName = intent.payload[PAYLOAD_TARGET]?.takeIf { it.isNotBlank() }
        if (targetName == null) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_USE_COMMAND, emptyMap()),
            )
            return
        }
        val targetId = playerGateway.findByName(targetName)
        if (targetId == null) {
            playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_PLAYER_NOT_FOUND,
                    mapOf("player" to targetName),
                ),
            )
            return
        }
        when (val outcome = transferLeader.transfer(player.id, targetId)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_TRANSFERRED,
                    mapOf("player" to targetName),
                ),
            )
            is Result.Failure -> {
                val err: PartyError = outcome.error
                playerGateway.sendMessage(
                    player.id,
                    messages.render(
                        player.id,
                        err.messageKey,
                        mapOf("player" to targetName),
                    ),
                )
            }
        }
    }

    public companion object {
        public const val INTENT_TRANSFER: String = "lobby.party.transfer-leader"
        public const val PAYLOAD_TARGET: String = "target"

        private val MSG_USE_COMMAND = MessageKey("astera.party.transfer.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.party.transfer.player-not-found")
        private val MSG_TRANSFERRED = MessageKey("astera.party.transfer.transferred")
    }
}
