package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.FriendshipError
import com.astarworks.astera.core.application.usecase.social.SendFriendRequestUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — send a friend request from the
 * Friends-list GUI.
 *
 * Triggered by the `lobby.friends.add` intent. Phase 1 PoC: the modal anvil
 * prompt primitive (`IGuiModalGateway`) does not exist yet, so the GUI
 * tile's role is to **close the GUI and direct the player to use
 * `/astera-friend add <name>`**. Once the modal lands (Wave 10c+), this
 * handler will read the typed name from `intent.payload["target"]` and call
 * the use case directly — the use case path below is already in place to
 * cover that future call site too.
 *
 * The handler still implements the use-case branch so testing the full
 * functional flow doesn't require a Paper harness: a hand-rolled intent
 * with `target=<name>` exercises the success / failure rendering.
 */
public class LobbyFriendAddIntentHandler(
    private val sendFriendRequest: SendFriendRequestUseCase,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_ADD) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyFriendAddIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val targetName = intent.payload[PAYLOAD_TARGET]?.takeIf { it.isNotBlank() }
        if (targetName == null) {
            // No target supplied — fall back to command-prompt UX.
            guiAdapter.close(player)
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
        when (val outcome = sendFriendRequest.send(player.id, targetId)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_SENT,
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
        public const val INTENT_ADD: String = "lobby.friends.add"
        public const val PAYLOAD_TARGET: String = "target"

        private val MSG_USE_COMMAND = MessageKey("astera.friends.add.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.friends.add.player-not-found")
        private val MSG_SENT = MessageKey("astera.friends.add.sent")
    }
}
