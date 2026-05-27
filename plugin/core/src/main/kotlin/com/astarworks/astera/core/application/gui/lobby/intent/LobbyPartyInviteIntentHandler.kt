package com.astarworks.astera.core.application.gui.lobby.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.usecase.social.JoinPartyUseCase
import com.astarworks.astera.core.application.usecase.social.PartyError
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import org.slf4j.LoggerFactory

/**
 * Wave G 5player-prefs-social (ADR-0124) — invite a player to your party.
 *
 * Triggered by `lobby.party.invite`. `payload[target]` carries the invitee
 * name; without a target the GUI closes and the player is told to use
 * `/astera-party invite <name>` (Phase 1 PoC posture — no anvil modal
 * primitive yet).
 *
 * # Why this doesn't use [InvitePartyMemberUseCase]
 *
 * [com.astarworks.astera.core.application.usecase.survival.InvitePartyMemberUseCase]
 * is **island-scoped** (Wave 5pve-survival / ADR-0107): it creates a
 * `PartyInvitation` row attached to an `IslandId`, with TTL + async accept.
 * The lobby Party flow has no island and no async accept primitive
 * (anvil-modal `IGuiModalGateway` lands Wave 10c+) — so Wave G's lobby
 * invite is a **synchronous add**: the caller (leader-only) inserts the
 * target into their `Party` via [JoinPartyUseCase]. From the target's
 * perspective they appear in the party-manager GUI on next open without
 * a confirmation prompt. ADR-0124 §"Invite vs join" records this PoC
 * trade-off; the proper async invite lands when the modal does.
 *
 * Leader-only is enforced inline: only the party leader's `invite` succeeds;
 * non-leader callers fail with [PartyError.NotLeader] so the GUI can
 * surface the right message.
 */
public class LobbyPartyInviteIntentHandler(
    private val joinParty: JoinPartyUseCase,
    private val parties: IPartyRepository,
    private val guiAdapter: IGuiAdapter,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        if (intent.key != INTENT_INVITE) {
            log.warn(
                "Unexpected intent '{}' routed to LobbyPartyInviteIntentHandler for player {}",
                intent.key, player.id,
            )
            return
        }
        val targetName = intent.payload[PAYLOAD_TARGET]?.takeIf { it.isNotBlank() }
        if (targetName == null) {
            guiAdapter.close(player)
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_USE_COMMAND, emptyMap()),
            )
            return
        }
        val party = parties.forPlayer(player.id)
        if (party == null) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_NO_PARTY, emptyMap()),
            )
            return
        }
        if (party.leader != player.id) {
            playerGateway.sendMessage(
                player.id,
                messages.render(player.id, MSG_NOT_LEADER, emptyMap()),
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
        when (val outcome = joinParty.join(targetId, party.id)) {
            is Result.Success -> playerGateway.sendMessage(
                player.id,
                messages.render(
                    player.id,
                    MSG_INVITED,
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
        public const val INTENT_INVITE: String = "lobby.party.invite"
        public const val PAYLOAD_TARGET: String = "target"

        private val MSG_USE_COMMAND = MessageKey("astera.party.invite.use-command")
        private val MSG_PLAYER_NOT_FOUND = MessageKey("astera.party.invite.player-not-found")
        private val MSG_NO_PARTY = MessageKey("astera.party.invite.no-party")
        private val MSG_NOT_LEADER = MessageKey("astera.party.invite.not-leader")
        private val MSG_INVITED = MessageKey("astera.party.invite.invited")
    }
}
