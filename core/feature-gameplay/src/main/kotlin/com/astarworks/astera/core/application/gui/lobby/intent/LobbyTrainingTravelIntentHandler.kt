package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IWorldGateway
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyTrainingRange
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey

/**
 * F3-11 (ADR-0200) — the lobby kit's "Training" button: `lobby.training.travel` teleports the player
 * to the training range spawn. [range] and [worlds] are looked up per click: the range exists only on
 * backends that host the lobby and the world gateway is replaced by the host's at a higher priority.
 */
public class LobbyTrainingTravelIntentHandler(
    private val range: () -> ILobbyTrainingRange?,
    private val worlds: () -> IWorldGateway?,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_TRAVEL) return
        val training = range()
        if (training == null) {
            send(player, MSG_UNAVAILABLE)
            return
        }
        val plan = training.plan
        val gateway = worlds() ?: run { send(player, MSG_UNAVAILABLE); return }
        gateway.teleportAsync(player.id, plan.spawn, training.world, plan.spawnYaw, 0f).thenAccept { moved ->
            send(player, if (moved) MSG_ARRIVED else MSG_UNAVAILABLE)
        }
    }

    private fun send(player: IMcPlayer, key: MessageKey) {
        playerGateway.sendMessage(player.id, messages.render(player.id, key, emptyMap()))
    }

    public companion object {
        public const val INTENT_TRAVEL: String = "lobby.training.travel"
        public val MSG_ARRIVED: MessageKey = MessageKey("astera.lobby.training.travel.arrived")
        public val MSG_UNAVAILABLE: MessageKey = MessageKey("astera.lobby.training.travel.unavailable")
    }
}
