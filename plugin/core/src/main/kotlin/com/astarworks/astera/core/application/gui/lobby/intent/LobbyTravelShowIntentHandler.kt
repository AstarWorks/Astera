package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.gui.lobby.TravelMenuGuiSpec
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelError
import com.astarworks.astera.core.application.usecase.travel.TravelToPveWorldUseCase
import com.astarworks.astera.core.application.usecase.travel.TravelToSurvivalIslandUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — routes `lobby.travel.*` [GuiIntent]s.
 *
 * Two intent keys are handled here:
 *
 *  - `lobby.travel.show` — opens [TravelMenuGuiSpec.SPEC] (sub-menu listing
 *    PvE / Survival / Lobby destinations). Each tile in the sub-menu emits
 *    one of the three concrete `lobby.travel.*` intents below.
 *  - `lobby.travel.pve` — dispatches into [TravelToPveWorldUseCase].
 *  - `lobby.travel.survival` — dispatches into [TravelToSurvivalIslandUseCase].
 *  - `lobby.travel.lobby` — dispatches into [ReturnToLobbyUseCase].
 *
 * The use cases already exist (Wave 5wireup ADR-0116 §G13-G15); this handler
 * is pure wireup. Errors are surfaced to the player via [IMessageRenderer] +
 * [IPlayerGateway] using the [TravelError.messageKey] each use case returns.
 *
 * # Per-plugin ownership
 *
 * Lives in `:plugin:core` because all three travel use cases are core-level
 * (no Match / Items / Economy dependencies). Wired by [com.astarworks.astera.core.platform.AsteraCorePlugin].
 *
 * # Intent namespace claim
 *
 * Subscribed via `IGuiIntentRouter.register("lobby.travel.")` so the whole
 * `lobby.travel.` prefix is claimed. Other plugins MUST NOT shadow this
 * prefix — adding a `lobby.travel.tutorial-realm` intent means extending
 * this handler, not registering a competing prefix.
 */
public class LobbyTravelShowIntentHandler(
    private val travelToPve: TravelToPveWorldUseCase,
    private val travelToSurvival: TravelToSurvivalIslandUseCase,
    private val returnToLobby: ReturnToLobbyUseCase,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_SHOW -> openSubMenu(player)
            INTENT_PVE -> dispatchTravel(player, TravelKind.PVE)
            INTENT_SURVIVAL -> dispatchTravel(player, TravelKind.SURVIVAL)
            INTENT_LOBBY -> dispatchTravel(player, TravelKind.LOBBY)
            else -> log.warn(
                "Unknown lobby.travel intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
        }
    }

    private fun openSubMenu(player: IMcPlayer) {
        val mcPlayer = mcServer.findPlayer(player.id)
        if (mcPlayer == null) {
            log.debug("Player {} disappeared before travel sub-menu open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, TravelMenuGuiSpec.SPEC)) {
            send(player, MSG_OPEN_REJECTED)
        }
    }

    private fun dispatchTravel(player: IMcPlayer, kind: TravelKind) {
        val outcome = when (kind) {
            TravelKind.PVE -> travelToPve.travel(player.id)
            TravelKind.SURVIVAL -> travelToSurvival.travel(player.id)
            TravelKind.LOBBY -> returnToLobby.returnToLobby(player.id)
        }
        when (outcome) {
            is Result.Success -> send(player, kind.successKey)
            is Result.Failure -> send(player, outcome.error.messageKey)
        }
    }

    private fun send(player: IMcPlayer, key: MessageKey) {
        playerGateway.sendMessage(player.id, messages.render(player.id, key, emptyMap()))
    }

    private enum class TravelKind(public val successKey: MessageKey) {
        PVE(MessageKey("astera.travel.success.pve")),
        SURVIVAL(MessageKey("astera.travel.success.survival")),
        LOBBY(MessageKey("astera.travel.success.lobby")),
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.travel."
        public const val INTENT_SHOW: String = "lobby.travel.show"
        public const val INTENT_PVE: String = "lobby.travel.pve"
        public const val INTENT_SURVIVAL: String = "lobby.travel.survival"
        public const val INTENT_LOBBY: String = "lobby.travel.lobby"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
    }
}
