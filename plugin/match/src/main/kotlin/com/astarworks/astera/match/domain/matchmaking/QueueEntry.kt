package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import java.time.Instant

/**
 * A single player's queue ticket (Wave 5w / ADR-0089).
 *
 * `enqueuedAt` is the FIFO ordering key. `partyId` (if present) groups
 * teammates so `IMatchmaker.tryFormMatch` keeps party members on the same
 * team in the resulting [MatchFormation].
 *
 * `estimatedWaitSec` is a coarse hint surfaced to lobby HUD; Phase 1 PoC
 * computes it from queue depth and per-mode average form rate. Phase 2 adds
 * rating fields here (additive) for skill-balanced matchmaking.
 */
public data class QueueEntry(
    val playerId: PlayerId,
    val mode: MatchmakingMode,
    val enqueuedAt: Instant,
    val estimatedWaitSec: Int,
    val partyId: PartyId? = null,
) {
    init {
        require(estimatedWaitSec >= 0) {
            "QueueEntry.estimatedWaitSec must be non-negative; got $estimatedWaitSec"
        }
    }
}
