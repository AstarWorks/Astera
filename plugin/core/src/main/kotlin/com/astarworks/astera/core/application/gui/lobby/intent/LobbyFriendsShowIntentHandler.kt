package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.FriendsListGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — opens the friends-list GUI in response to
 * the `lobby.friends.show` intent.
 *
 * Wave 5e shipped the Friendship domain + use cases
 * ([com.astarworks.astera.core.application.usecase.social.SendFriendRequestUseCase],
 * [com.astarworks.astera.core.application.usecase.social.AcceptFriendRequestUseCase],
 * etc.); this wave wires the **GUI surface** that lets a player open them
 * from the hotbar — actual friend-list rendering pulls live data through
 * `DefaultGuiBindingResolver` (Wave 5flex extended bindings) when a future
 * wave adds `player.friends.*` bindings. For Phase 1 PoC the spec is
 * skeletal — close button + placeholder tiles.
 */
public class LobbyFriendsShowIntentHandler(
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_SHOW) {
            log.warn(
                "Unknown lobby.friends intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
            return
        }
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before friends GUI open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, FriendsListGuiSpec.SPEC)) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_OPEN_REJECTED, emptyMap()),
            )
        }
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.friends."
        public const val INTENT_SHOW: String = "lobby.friends.show"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
