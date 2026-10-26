package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IFriendshipRepository
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.social.FriendshipStatus
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — list pending friend requests.
 *
 * Triggered by `lobby.friends.pending`. Phase 1 PoC: there is no dedicated
 * "pending requests" GUI yet, so the handler renders the list as a chat
 * sequence. Each pending row shows the requester's display name, and the
 * footer hints at `/astera friend accept <name>` as the next step. When
 * the proper GUI lands (Wave 10c+) this handler will open it via
 * `IGuiAdapter.open` instead.
 *
 * "Pending for this player" means `Friendship.status == PENDING` and the
 * player is not the side that sent the request. Wave 5e's `Friendship`
 * domain doesn't carry an explicit `sender` field — `playerA` is the
 * canonical first side and the use case picks `from = playerA` when
 * creating the row. So a request is **pending for [player]** when the row
 * has `playerB == player.id`.
 */
public class LobbyFriendsPendingIntentHandler(
    private val friendships: IFriendshipRepository,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_PENDING) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyFriendsPendingIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val pending = friendships.forPlayer(player.id)
            .filter { it.status == FriendshipStatus.PENDING && it.playerB == player.id }
        if (pending.isEmpty()) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_NONE, emptyMap()),
            )
            return
        }
        playerGateway.sendMessage(
            player.id,
            messages.render(
                player.id,
                MSG_HEADER,
                mapOf("count" to pending.size.toString()),
            ),
        )
        pending.forEachIndexed { idx, row ->
            val name = mcServer.findPlayer(row.playerA)?.name ?: row.playerA.uuid.toString()
            playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_ROW,
                    mapOf(
                        "index" to (idx + 1).toString(),
                        "player" to name,
                    ),
                ),
            )
        }
        playerGateway.sendMessage(
            player.id,
            messages.render(player.id, MSG_FOOTER, emptyMap()),
        )
    }

    public companion object {
        public const val INTENT_PENDING: String = "lobby.friends.pending"

        private val MSG_NONE = MessageKey("astera.friends.pending.none")
        private val MSG_HEADER = MessageKey("astera.friends.pending.header")
        private val MSG_ROW = MessageKey("astera.friends.pending.row")
        private val MSG_FOOTER = MessageKey("astera.friends.pending.footer")
    }
}
