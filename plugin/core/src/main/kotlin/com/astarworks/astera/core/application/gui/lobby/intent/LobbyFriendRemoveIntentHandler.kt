package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.FriendshipError
import com.astarworks.astera.core.application.usecase.social.RemoveFriendUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — remove a friendship.
 *
 * Triggered by `lobby.friends.remove`. `payload[target]` carries the friend's
 * name (typically the friend tile's username); without a target the player
 * is told to use the Brigadier command.
 */
public class LobbyFriendRemoveIntentHandler(
    private val removeFriend: RemoveFriendUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_REMOVE) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyFriendRemoveIntentHandler for player {}",
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
        when (val outcome = removeFriend.remove(player.id, targetId)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_REMOVED,
                    mapOf("player" to targetName),
                ),
            )
            is Result.Failure -> {
                val err: FriendshipError = outcome.error
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
        public const val INTENT_REMOVE: String = "lobby.friends.remove"
        public const val PAYLOAD_TARGET: String = "target"

        private val MSG_USE_COMMAND = MessageKey("astera.friends.remove.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.friends.remove.player-not-found")
        private val MSG_REMOVED = MessageKey("astera.friends.remove.removed")
    }
}
