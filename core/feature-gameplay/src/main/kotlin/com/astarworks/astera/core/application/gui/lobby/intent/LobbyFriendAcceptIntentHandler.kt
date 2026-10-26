package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.AcceptFriendRequestUseCase
import com.astarworks.astera.core.application.usecase.social.FriendshipError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — accept a pending friend request.
 *
 * Triggered by `lobby.friends.accept`. The intent's `payload[requester]` is
 * the username of the person who sent the request — the GUI's pending-list
 * row stamps it onto the intent when rendered. Missing / blank payload
 * directs the player to the Brigadier command path.
 */
public class LobbyFriendAcceptIntentHandler(
    private val acceptFriendRequest: AcceptFriendRequestUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_ACCEPT) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyFriendAcceptIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val requesterName = intent.payload[PAYLOAD_REQUESTER]?.takeIf { it.isNotBlank() }
        if (requesterName == null) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_USE_COMMAND, emptyMap()),
            )
            return
        }
        val requesterId = playerGateway.findByName(requesterName)
        if (requesterId == null) {
            playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_PLAYER_NOT_FOUND,
                    mapOf("player" to requesterName),
                ),
            )
            return
        }
        when (val outcome = acceptFriendRequest.accept(player.id, requesterId)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_ACCEPTED,
                    mapOf("player" to requesterName),
                ),
            )
            is Result.Failure -> {
                val err: FriendshipError = outcome.error
                playerGateway.sendMessage(
                    player.id,
                    messages.render(
                        player.id,
                        err.messageKey,
                        mapOf("player" to requesterName),
                    ),
                )
            }
        }
    }

    public companion object {
        public const val INTENT_ACCEPT: String = "lobby.friends.accept"
        public const val PAYLOAD_REQUESTER: String = "requester"

        private val MSG_USE_COMMAND = MessageKey("astera.friends.accept.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.friends.accept.player-not-found")
        private val MSG_ACCEPTED = MessageKey("astera.friends.accept.accepted")
    }
}
