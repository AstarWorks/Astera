package com.astarworks.astera.match.application.intent

import com.astarworks.astera.core.adapter.api.entity.IMcPlayer
import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IGuiAdapter
import com.astarworks.astera.core.application.port.outbound.IMessageRenderer
import com.astarworks.astera.core.application.port.outbound.IPlayerGateway
import com.astarworks.astera.core.application.port.outbound.presentation.IPresentation
import com.astarworks.astera.core.domain.gui.GuiIntent
import com.astarworks.astera.core.domain.gui.GuiText
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.LobbyLanePadLayout
import com.astarworks.astera.core.domain.presentation.PresentationMoments
import com.astarworks.astera.match.application.gui.lobby.QueueModeListGuiSpec
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.application.service.matchmaking.BotMatchRequester
import com.astarworks.astera.match.application.service.matchmaking.LaneQueueJoiner
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.matchmaking.LaneKind
import com.astarworks.astera.match.domain.matchmaking.QueueLane
import com.astarworks.astera.match.domain.matchmaking.QueueSettings
import org.slf4j.LoggerFactory

/**
 * Routes `lobby.queue.*` [GuiIntent]s (Wave 5lobby-content ADR-0119, reworked by ADR-0248).
 *
 * Lives in `:plugin:match` because it depends on the matchmaker and the lane list; ADR-0022 forbids
 * `:plugin:core` from importing Match types.
 *
 * # Intent keys
 *
 *  - `lobby.queue.show` - opens the mode picker ([QueueModeListGuiSpec]): every lane with its description, size,
 *    minimum players and queued count, a Queue and a Play-vs-bots button each.
 *  - `lobby.queue.join` - queues the clicking player (and their party) in `payload[lane]` with real players.
 *  - `lobby.queue.bots` - same, then asks the bot runner to fill the lane ([BotMatchRequester]).
 *  - `lobby.queue.leave` - takes the player out of their queue.
 *
 * `lobby.queue.join` still accepts the old `modeKey` payload (`custom:<id>`).
 *
 * Per the longest-prefix-wins rule of [com.astarworks.astera.core.application.port.inbound.IGuiIntentRouter]
 * the prefix `"lobby.queue."` is claimed by this handler; other plugins MUST NOT shadow it.
 */
public class LobbyQueueIntentHandler(
    private val matchmaker: IMatchmaker,
    private val joiner: LaneQueueJoiner,
    private val settings: () -> QueueSettings,
    private val guiAdapter: IGuiAdapter,
    private val mcServer: IMcServer,
    private val messages: IMessageRenderer,
    private val playerGateway: IPlayerGateway,
    private val bots: BotMatchRequester?,
    private val presentation: () -> IPresentation? = { null },
) {

    private val log = LoggerFactory.getLogger(javaClass)

    public fun handle(intent: GuiIntent, player: IMcPlayer) {
        when (intent.key) {
            INTENT_SHOW -> openPicker(player)
            INTENT_JOIN -> choose(intent, player, withBots = false)
            INTENT_BOTS -> choose(intent, player, withBots = true)
            INTENT_LEAVE -> leave(player)
            else -> log.warn(
                "Unknown lobby.queue intent '{}' from player {} - payload={}",
                intent.key, player.id, intent.payload,
            )
        }
    }

    private fun openPicker(player: IMcPlayer) {
        val mcPlayer = mcServer.findPlayer(player.id) ?: run {
            log.debug("Player {} disappeared before queue picker open", player.id)
            return
        }
        val config = settings()
        val mine = matchmaker.queueStatus(player.id)?.let { config.laneFor(it.mode) }
        val views = config.lanes.values.filter { it.kind != LaneKind.PVE }.take(QueueModeListGuiSpec.MAX_LANES).map { lane -> view(lane, mine) }
        log.info("Play picker opened for {} ({} lanes, queued in {})", player.name, views.size, mine?.key ?: "-")
        if (!guiAdapter.open(mcPlayer, QueueModeListGuiSpec.build(views, mine?.let { LobbyLanePadLayout.displayName(it.key) }))) {
            send(player, MSG_OPEN_REJECTED)
        }
    }

    private fun view(lane: QueueLane, mine: QueueLane?): QueueModeListGuiSpec.LaneView {
        val queued = matchmaker.pendingByMode(lane.mode).size
        return QueueModeListGuiSpec.LaneView(
            laneKey = lane.key,
            name = GuiText(known(MessageKey("astera.gamemode.${lane.key}.title"))
                ?: MessageKey("astera.play.lane.generic.name"), mapOf("lane" to LobbyLanePadLayout.displayName(lane.key))),
            description = GuiText(known(MessageKey("astera.play.lane.${lane.key}.desc")) ?: MessageKey("astera.play.lane.generic.desc")),
            detail = known(MessageKey("astera.play.lane.${lane.key}.detail"))?.let { GuiText(it) },
            laneLabel = LobbyLanePadLayout.displayName(lane.key),
            teams = lane.teams,
            playersPerTeam = lane.playersPerTeam,
            minPlayers = lane.minPlayers,
            queued = queued,
            botCount = bots?.botsNeeded(lane, queued + if (mine == null || mine.key != lane.key) 1 else 0) ?: 0,
            mine = mine?.key == lane.key,
        )
    }

    /** [key] when the catalogue knows it (the renderer echoes unknown keys back). */
    private fun known(key: MessageKey): MessageKey? = key.takeIf { messages.render(null, it) != it.value }

    private fun choose(intent: GuiIntent, player: IMcPlayer, withBots: Boolean) {
        val lane = resolveLane(intent)
        if (lane == null) {
            log.warn("lobby.queue intent without a usable lane from {}: {}", player.name, intent.payload)
            send(player, MSG_MODE_UNKNOWN)
            return
        }
        val label = LobbyLanePadLayout.displayName(lane.key)
        val placeholders = mapOf("lane" to label, "mode" to label)
        when (val outcome = joiner.join(player.id, lane)) {
            is LaneQueueJoiner.Outcome.Joined -> {
                log.info("{} joined lane '{}' from the picker{}", player.name, lane.key, if (withBots) " (vs bots)" else "")
                announceJoined(player, placeholders)
            }
            is LaneQueueJoiner.Outcome.Switched -> {
                log.info("{} moved to lane '{}' from {} via the picker", player.name, lane.key, outcome.from)
                announceJoined(player, placeholders)
            }
            is LaneQueueJoiner.Outcome.AlreadyHere -> {
                log.info("{} is already queued in lane '{}'", player.name, lane.key)
                if (!withBots) send(player, MSG_ALREADY_HERE, placeholders)
            }
            is LaneQueueJoiner.Outcome.InMatch -> {
                presentation()?.play(PresentationMoments.UI_DENY, player.id)
                send(player, MSG_IN_MATCH)
                return
            }
            is LaneQueueJoiner.Outcome.Rejected -> {
                log.info("{} could not join lane '{}': {}", player.name, lane.key, outcome.error)
                presentation()?.play(PresentationMoments.UI_DENY, player.id)
                send(player, mapQueueError(outcome.error), placeholders)
                return
            }
        }
        if (withBots) {
            val queuedNow = matchmaker.pendingByMode(lane.mode).size
            val requester = bots
            if (requester == null) send(player, MSG_BOTS_UNAVAILABLE) else requester.request(player.id, lane, queuedNow)
        }
        mcServer.findPlayer(player.id)?.let { guiAdapter.close(it) }
    }

    private fun announceJoined(player: IMcPlayer, placeholders: Map<String, String>) {
        presentation()?.play(PresentationMoments.UI_CONFIRM, player.id)
        mcServer.findPlayer(player.id)?.sendTitle(MSG_JOINED_TITLE, MSG_JOINED_SUBTITLE)
        send(player, MSG_JOINED, placeholders)
    }

    private fun leave(player: IMcPlayer) {
        val lane = matchmaker.queueStatus(player.id)?.let { settings().laneFor(it.mode) }
        val left = joiner.leave(player.id)
        if (left == null) {
            send(player, MSG_NOT_QUEUED)
        } else {
            log.info("{} left the queue via the menu (lane {})", player.name, lane?.key ?: left.mode)
            send(player, MSG_LEFT, mapOf("lane" to (lane?.key?.let(LobbyLanePadLayout::displayName) ?: "")))
        }
        mcServer.findPlayer(player.id)?.let { guiAdapter.close(it) }
    }

    private fun resolveLane(intent: GuiIntent): QueueLane? {
        val config = settings()
        intent.payload[QueueModeListGuiSpec.PAYLOAD_LANE]?.takeIf { it.isNotBlank() }?.let { return config.lanes[it] }
        val mode = intent.payload[PAYLOAD_MODE_KEY]?.takeIf { it.isNotBlank() }?.let(MatchmakingMode::parse)
        return mode?.let { config.laneFor(it) }
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
        public const val INTENT_BOTS: String = "lobby.queue.bots"
        public const val INTENT_LEAVE: String = "lobby.queue.leave"
        public const val PAYLOAD_MODE_KEY: String = "modeKey"

        private val MSG_OPEN_REJECTED = MessageKey("astera.gui.error.open-rejected")
        private val MSG_MODE_UNKNOWN = MessageKey("astera.match.queue.unknown-mode")
        private val MSG_ALREADY_QUEUED = MessageKey("astera.match.queue.join.already-queued")
        private val MSG_NOT_QUEUED = MessageKey("astera.match.queue.join.not-queued")
        private val MSG_MODE_CLOSED = MessageKey("astera.match.queue.join.mode-closed")
        private val MSG_PARTY_TOO_LARGE = MessageKey("astera.match.queue.join.party-too-large")
        private val MSG_IN_MATCH = MessageKey("astera.match.queue.join.in-match")
        private val MSG_JOINED = MessageKey("astera.play.queue.joined")
        private val MSG_ALREADY_HERE = MessageKey("astera.play.queue.already-here")
        private val MSG_LEFT = MessageKey("astera.play.queue.left")
        private val MSG_BOTS_UNAVAILABLE = MessageKey("astera.play.bots.unavailable")
        private val MSG_JOINED_TITLE = MessageKey("astera.lobby.lane.joined.title")
        private val MSG_JOINED_SUBTITLE = MessageKey("astera.lobby.lane.joined.subtitle")
    }
}
