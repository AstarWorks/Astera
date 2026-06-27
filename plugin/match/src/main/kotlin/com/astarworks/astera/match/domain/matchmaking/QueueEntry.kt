package com.astarworks.astera.match.domain.matchmaking

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.match.domain.rating.Rating
import java.time.Instant

/**
 * A single player's queue ticket (Wave 5w / ADR-0089).
 *
 * `enqueuedAt` is the FIFO ordering key. `partyId` (if present) groups
 * teammates so `IMatchmaker.tryFormMatch` keeps party members on the same
 * team in the resulting [MatchFormation].
 *
 * `estimatedWaitSec` is a coarse hint surfaced to lobby HUD; Phase 1 PoC
 * computes it from queue depth and per-mode average form rate.
 *
 * `rating` is the player's Elo at enqueue time (Wave 7b, ADR-0037); the
 * matchmaker uses it to balance solo players across teams. Null when the
 * matchmaker has no rating source wired — kept additive per ADR-0089 §"Phase 2
 * Elo 拡張" (a field on the entry, **not** a new [MatchmakingMode] variant).
 */
public data class QueueEntry(
    val playerId: PlayerId,
    val mode: MatchmakingMode,
    val enqueuedAt: Instant,
    val estimatedWaitSec: Int,
    val partyId: PartyId? = null,
    val rating: Rating? = null,
) {
    init {
        require(estimatedWaitSec >= 0) {
            "QueueEntry.estimatedWaitSec must be non-negative; got $estimatedWaitSec"
        }
    }
}
