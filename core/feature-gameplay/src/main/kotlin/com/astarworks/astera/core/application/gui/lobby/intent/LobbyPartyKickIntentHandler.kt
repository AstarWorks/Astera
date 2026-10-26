package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.KickPartyMemberUseCase
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — kick a member from your party.
 *
 * Triggered by `lobby.party.kick`. Leader-only — the use case
 * [KickPartyMemberUseCase] enforces this and surfaces [PartyError.NotLeader]
 * if a non-leader fires the intent (e.g., a payload-spoofed click).
 *
 * `payload[target]` is the kicked player's name; absent payload directs the
 * caller to `/astera party kick <name>`.
 */
public class LobbyPartyKickIntentHandler(
    private val kickPartyMember: KickPartyMemberUseCase,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public suspend fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_KICK) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyPartyKickIntentHandler for player {}",
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
        when (val outcome = kickPartyMember.kick(player.id, targetId)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_KICKED,
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
        public const val INTENT_KICK: String = "lobby.party.kick"
        public const val PAYLOAD_TARGET: String = "target"

        private val MSG_USE_COMMAND = MessageKey("astera.party.kick.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.party.kick.player-not-found")
        private val MSG_KICKED = MessageKey("astera.party.kick.kicked")
    }
}
