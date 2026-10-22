package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.adapter.api.server.IMcServer
import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.application.port.outbound.lobby.ILobbyLaneBoard
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.LobbyLanePadEntered
import com.astarworks.astera.core.domain.i18n.MessageKey
import com.astarworks.astera.core.domain.lobby.LobbyLanePadLayout
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.matchmaking.QueueSettings

/**
 * F3-1: the lobby lane pads. Stepping onto a pad ([LobbyLanePadEntered]) joins that lane's queue
 * with a title + actionbar confirmation; stepping off does nothing (leaving is `/astera queue
 * leave` or the menu). Also keeps [ILobbyLaneBoard] (the pad holograms) in sync with the queues.
 *
 * A player already queued in another lane is moved to the pad's lane; a player in a match is
 * told they cannot queue.
 */
public class LobbyLanePadJoinService(
    private val matchmaker: IMatchmaker,
    private val parties: IPartyRepository,
    private val matches: IMatchRepository,
    private val mcServer: IMcServer,
    private val settings: () -> QueueSettings,
    private val board: ILobbyLaneBoard?,
) {

    public fun onPadEntered(event: LobbyLanePadEntered) {
        val lane = settings().lanes[event.laneId] ?: return
        val player = mcServer.findPlayer(event.playerId)
        val laneName = LobbyLanePadLayout.displayName(lane.key)
        val ph = mapOf("lane" to laneName)

        if (matches.findActiveMatches().any { m -> m.teams.any { event.playerId in it.members } }) {
            player?.sendActionBar(K_IN_MATCH, ph)
            return
        }
        val partyId = parties.forPlayer(event.playerId)?.id
        var result = matchmaker.enqueue(event.playerId, lane.mode, partyId)
        var switched = false
        val failure = (result as? Result.Failure)?.error
        if (failure is QueueError.AlreadyEnqueued) {
            if (failure.mode == lane.mode) {
                player?.sendActionBar(K_ALREADY, ph)
                return
            }
            matchmaker.dequeue(event.playerId)
            result = matchmaker.enqueue(event.playerId, lane.mode, partyId)
            switched = true
        }
        when (result) {
            is Result.Success -> {
                player?.sendTitle(K_TITLE, K_SUBTITLE)
                player?.sendActionBar(if (switched) K_SWITCHED else K_JOINED, ph)
            }
            is Result.Failure -> player?.sendActionBar(errorKey(result.error), ph)
        }
        refreshBoard()
    }

    /** Pushes the queued count of every lane to the hologram board. */
    public fun refreshBoard() {
        val target = board ?: return
        for (lane in settings().lanes.values) {
            target.setQueued(lane.key, matchmaker.pendingByMode(lane.mode).size)
        }
    }

    private fun errorKey(error: QueueError): MessageKey = when (error) {
        is QueueError.ModeClosed -> K_CLOSED
        is QueueError.PartyTooLargeForMode -> K_PARTY_TOO_LARGE
        is QueueError.AlreadyEnqueued -> K_ALREADY
        is QueueError.NotEnqueued -> K_FAILED
    }

    private companion object {
        val K_TITLE = MessageKey("astera.lobby.lane.joined.title")
        val K_SUBTITLE = MessageKey("astera.lobby.lane.joined.subtitle")
        val K_JOINED = MessageKey("astera.lobby.lane.joined.actionbar")
        val K_SWITCHED = MessageKey("astera.lobby.lane.switched.actionbar")
        val K_ALREADY = MessageKey("astera.lobby.lane.already.actionbar")
        val K_IN_MATCH = MessageKey("astera.lobby.lane.in-match.actionbar")
        val K_CLOSED = MessageKey("astera.lobby.lane.closed.actionbar")
        val K_PARTY_TOO_LARGE = MessageKey("astera.lobby.lane.party-too-large.actionbar")
        val K_FAILED = MessageKey("astera.lobby.lane.failed.actionbar")
    }
}
