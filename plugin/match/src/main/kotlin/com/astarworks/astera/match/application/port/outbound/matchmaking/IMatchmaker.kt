package com.astarworks.astera.match.application.port.outbound.matchmaking

import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.social.PartyId
import com.astarworks.astera.match.domain.matchmaking.MatchFormation
import com.astarworks.astera.match.domain.matchmaking.MatchmakingMode
import com.astarworks.astera.match.domain.matchmaking.QueueEntry
import com.astarworks.astera.match.domain.matchmaking.QueueError

/**
 * Outbound port (Wave 5w / ADR-0089 δ): Hypixel-style match queue.
 *
 * Phase 1 PoC ships `InMemoryMatchmaker` (FIFO + party-aware grouping).
 * Phase 2 swaps in an Elo / Glicko2-balanced impl per
 * `idea-phase2-roadmap.md` 軸 A. The port shape is **additive-forward**: a
 * future `rating: Int` field on [QueueEntry] does not change this interface.
 *
 * **Idempotency contract** ([tryFormMatch]): once a formation is returned,
 * the underlying entries are removed from pending state atomically.
 * Subsequent calls must not return the same player ids. Konsist enforces a
 * marker rule (see `ArchitectureTest.kt` /
 * `IMatchmaker tryFormMatch impls must declare idempotent contract`).
 */
public interface IMatchmaker {

    /** Enqueue [playerId] under [mode]. Party members share a single [partyId]. */
    public fun enqueue(
        playerId: PlayerId,
        mode: MatchmakingMode,
        partyId: PartyId? = null,
    ): Result<QueueEntry, QueueError>

    /** Remove [playerId] from whichever queue they are in. */
    public fun dequeue(playerId: PlayerId): Result<Unit, QueueError>

    /** Current entry for [playerId], or null if not queued. */
    public fun queueStatus(playerId: PlayerId): QueueEntry?

    /** Snapshot of entries pending for [mode], FIFO-ordered. */
    public fun pendingByMode(mode: MatchmakingMode): Collection<QueueEntry>

    /**
     * Attempt to form a complete match from [mode]'s pending queue.
     *
     * Returns:
     *  - `Result.Success(formation)` — a formation was assembled; the
     *    underlying entries have been removed from pending. Subsequent
     *    calls do not see them.
     *  - `Result.Success(null)` — not enough pending entries to form a
     *    match yet. No state mutation.
     *  - `Result.Failure(QueueError.ModeClosed)` — admin closed [mode].
     */
    public fun tryFormMatch(mode: MatchmakingMode): Result<MatchFormation?, QueueError>
}
