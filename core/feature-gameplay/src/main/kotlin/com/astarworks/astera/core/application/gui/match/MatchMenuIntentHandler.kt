package com.astarworks.astera.core.application.gui.match

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.travel.ReturnToLobbyUseCase
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.player.PlayerId
import org.slf4j.LoggerFactory

/**
 * UI-06 - intents behind the in-match mini menu (`content/gui/match-menu.yaml`, opened with F during a match).
 *
 *  - `match.menu.leave` opens the confirm screen ([MatchMenuGuiSpec.CONFIRM]).
 *  - `match.menu.leave.confirm` runs the existing leave-to-lobby flow ([ReturnToLobbyUseCase], which
 *    publishes `PlayerLeavingForLobby`: stars burst, forfeit rules apply). Nothing is duplicated here.
 *  - `match.menu.help` prints what Norma, stars and the weapon limit mean.
 *
 * Settings needs no intent: its tile is `gui.open {gui: settings}`.
 */
public class MatchMenuIntentHandler(
    private val returnToLobby: ReturnToLobbyUseCase,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_LEAVE -> if (!guiAdapter.open(player, MatchMenuGuiSpec.CONFIRM)) send(player.id, MSG_OPEN_REJECTED)
            INTENT_LEAVE_CONFIRM -> {
                guiAdapter.close(player)
                when (val outcome = returnToLobby.returnToLobby(player.id)) {
                    is Result.Success -> send(player.id, MSG_LEFT)
                    is Result.Failure -> send(player.id, outcome.error.messageKey)
                }
            }
            INTENT_HELP -> {
                guiAdapter.close(player)
                HELP_LINES.forEach { send(player.id, it) }
            }
            else -> log.warn("Unknown match.menu intent '{}' from {}", intent.key, player.id)
        }
    }

    /** The one-line "F: menu" hint shown when a player enters a match. */
    public fun sendEntryHint(playerId: PlayerId) {
        send(playerId, MSG_HINT)
    }

    private fun send(playerId: PlayerId, key: MessageKey) {
        playerGateway.sendMessage(playerId, messages.render(playerId, key, emptyMap()))
    }

    public companion object {
        public const val INTENT_PREFIX: String = "match.menu."
        public const val INTENT_LEAVE: String = "match.menu.leave"
        public const val INTENT_LEAVE_CONFIRM: String = "match.menu.leave.confirm"
        public const val INTENT_HELP: String = "match.menu.help"

        /** The YAML menu opened by F. */
        public const val MENU_GUI_ID: String = "match-menu"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
        private val MSG_LEFT = MessageKey("astera.travel.success.lobby")
        private val MSG_HINT = MessageKey("astera.match_menu.hint")
        private val HELP_LINES = listOf(
            MessageKey("astera.match_menu.help.header"),
            MessageKey("astera.match_menu.help.norma"),
            MessageKey("astera.match_menu.help.stars"),
            MessageKey("astera.match_menu.help.weapon-limit"),
        )
    }
}
