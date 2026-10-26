package com.astarworks.astera.match.application.service.matchmaking

import com.astarworks.astera.core.application.port.outbound.IMatchRepository
import com.astarworks.astera.core.application.port.outbound.IPartyRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.match.application.port.outbound.matchmaking.IMatchmaker
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import com.astarworks.astera.match.domain.matchmaking.QueueError
import com.astarworks.astera.match.domain.matchmaking.QueueLane

/**
 * Joins / leaves a queue lane on behalf of a menu click (ADR-0248): the one place that knows "a player in a
 * match cannot queue", "queueing elsewhere moves you" and "parties queue together".
 */
public class LaneQueueJoiner(
    private val matchmaker: IMatchmaker,
    private val parties: IPartyRepository,
    private val matches: IMatchRepository,
) {

    public sealed interface Outcome {
        /** Newly queued in the lane. */
        public data class Joined(val entry: QueueEntry) : Outcome

        /** Was queued in another lane and was moved. */
        public data class Switched(val entry: QueueEntry, val from: String) : Outcome

        /** Already waiting in exactly this lane. */
        public data class AlreadyHere(val entry: QueueEntry) : Outcome

        public data object InMatch : Outcome

        public data class Rejected(val error: QueueError) : Outcome
    }

    public suspend fun join(player: PlayerId, lane: QueueLane): Outcome {
        if (matches.findActiveMatches().any { m -> m.teams.any { player in it.members } }) return Outcome.InMatch
        val existing = matchmaker.queueStatus(player)
        if (existing != null && existing.mode == lane.mode) return Outcome.AlreadyHere(existing)
        val partyId = parties.forPlayer(player)?.id
        var from: String? = null
        if (existing != null) {
            from = existing.mode.toString()
            matchmaker.dequeue(player)
        }
        return when (val result = matchmaker.enqueue(player, lane.mode, partyId)) {
            is Result.Success -> if (from != null) Outcome.Switched(result.value, from) else Outcome.Joined(result.value)
            is Result.Failure -> Outcome.Rejected(result.error)
        }
    }

    /** Takes [player] out of whatever queue they wait in; the entry they left, or `null` when not queued. */
    public fun leave(player: PlayerId): QueueEntry? {
        val entry = matchmaker.queueStatus(player) ?: return null
        return when (matchmaker.dequeue(player)) {
            is Result.Success -> entry
            is Result.Failure -> null
        }
    }
}
