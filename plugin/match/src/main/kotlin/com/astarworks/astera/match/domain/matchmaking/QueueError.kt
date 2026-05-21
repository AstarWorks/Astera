package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Sealed error type for [IMatchmaker] operations (Wave 5w / ADR-0089).
 */
public sealed interface QueueError {

    /** [IMatchmaker.enqueue] called for a player already in a queue. */
    public data class AlreadyEnqueued(val playerId: PlayerId, val mode: MatchmakingMode) : QueueError

    /** [IMatchmaker.dequeue] called for a player not currently queued. */
    public data class NotEnqueued(val playerId: PlayerId) : QueueError

    /** Admin / scheduler closed the requested mode. */
    public data class ModeClosed(val mode: MatchmakingMode) : QueueError

    /** Party size exceeds the mode's per-team cap. */
    public data class PartyTooLargeForMode(
        val mode: MatchmakingMode,
        val partySize: Int,
        val perTeamCap: Int,
    ) : QueueError
}
