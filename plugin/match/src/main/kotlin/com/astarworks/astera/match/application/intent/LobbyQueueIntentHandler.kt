package com.astarworks.astera.match.application.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.match.application.gui.lobby.QueueModeListGuiSpec
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError
import org.slf4j.LoggerFactory

/**
 * Wave 5lobby-content (ADR-0119) — routes `lobby.queue.*` [GuiIntent]s to
 * the Wave 5w [IMatchmaker] port.
 *
 * Lives in `:plugin:match` because it depends on [IMatchmaker] +
 * [MatchmakingMode], both of which live in Match. ADR-0022 forbids
 * `:plugin:core` from importing Match types; routing the lobby's
 * matchmaking-related intents from inside Match keeps that boundary intact.
 *
 * # Intent keys
 *
 *  - `lobby.queue.show` — opens [QueueModeListGuiSpec.SPEC], a sub-menu
 *    listing the available [MatchmakingMode]s. Each tile in the sub-menu
 *    emits a `lobby.queue.join` intent with the chosen `modeKey` payload.
 *  - `lobby.queue.join` — enqueues the clicking player in the requested
 *    mode (resolves their [PartyId] from [IPartyRepository] so party members
 *    queue together — Wave 5w `IMatchmaker.enqueue` party-grouping
 *    contract).
 *
 * Payload contract for `lobby.queue.join`:
 *  - `modeKey` — one of `norma`, `custom:<game-mode-id>`,
 *    `tournament:<tournament-id>`. Missing / malformed values log at WARN
 *    and surface a generic failure message to the player.
 *
 * Per the longest-prefix-wins rule of [com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter]
 * the prefix `"lobby.queue."` is claimed by this handler; other plugins MUST
 * NOT shadow it.
 */
public class LobbyQueueIntentHandler(
    private val matchmaker: IMatchmaker,
    private val parties: IPartyRepository,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_SHOW -> openModeList(player)
            INTENT_JOIN -> joinQueue(intent, player)
            else -> log.warn(
                "Unknown lobby.queue intent '{}' from player {} — payload={}",
                intent.key, player.id, intent.payload,
            )
        }
    }

    private fun openModeList(player: IMcPlayer) {
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before queue mode list open", player.id)
            return
        }
        if (!guiAdapter.open(mcPlayer, QueueModeListGuiSpec.SPEC)) {
            send(player, MSG_OPEN_REJECTED)
        }
    }

    private fun joinQueue(intent: GuiIntent, player: IMcPlayer) {
        val modeKey = intent.payload[PAYLOAD_MODE_KEY]
        if (modeKey.isNullOrBlank()) {
            log.warn(
                "lobby.queue.join intent missing '{}' payload from player {}: {}",
                PAYLOAD_MODE_KEY, player.id, intent.payload,
            )
            send(player, MSG_MODE_UNKNOWN)
            return
        }
        val mode = MatchmakingMode.parse(modeKey)
        if (mode == null) {
            log.warn(
                "lobby.queue.join intent has unparseable modeKey '{}' from player {}",
                modeKey, player.id,
            )
            send(player, MSG_MODE_UNKNOWN)
            return
        }
        val partyId = parties.forPlayer(player.id)?.id
        when (val outcome = matchmaker.enqueue(player.id, mode, partyId)) {
            is Result.Success -> send(player, MSG_JOIN_SUCCESS, mapOf("mode" to modeKey))
            is Result.Failure -> send(player, mapQueueError(outcome.error), mapOf("mode" to modeKey))
        }
    }

    private fun mapQueueError(error: QueueError): MessageKey = when (error) {
        is QueueError.AlreadyEnqueued -> MSG_ALREADY_QUEUED
        is QueueError.NotEnqueued -> MSG_NOT_QUEUED
        is QueueError.ModeClosed -> MSG_MODE_CLOSED
        is QueueError.PartyTooLargeForMode -> MSG_PARTY_TOO_LARGE
    }

    private fun send(player: IMcPlayer, key: MessageKey, placeholders: Map<String, String> = emptyMap()) {
        playerGateway.sendMessage(player.id, messages.render(player.id, key, placeholders))
    }

    public companion object {
        public const val INTENT_PREFIX: String = "lobby.queue."
        public const val INTENT_SHOW: String = "lobby.queue.show"
        public const val INTENT_JOIN: String = "lobby.queue.join"
        public const val PAYLOAD_MODE_KEY: String = "modeKey"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
        private val MSG_MODE_UNKNOWN = MessageKey("astera.match.queue.unknown-mode")
        private val MSG_JOIN_SUCCESS = MessageKey("astera.match.queue.join.success")
        private val MSG_ALREADY_QUEUED = MessageKey("astera.match.queue.join.already-queued")
        private val MSG_NOT_QUEUED = MessageKey("astera.match.queue.join.not-queued")
        private val MSG_MODE_CLOSED = MessageKey("astera.match.queue.join.mode-closed")
        private val MSG_PARTY_TOO_LARGE = MessageKey("astera.match.queue.join.party-too-large")
    }
}
