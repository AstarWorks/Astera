package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.tutorial.TutorialRunnerSnapshot

/**
 * Per-player persisted tutorial sequencer state (Wave 5p, ADR-0088).
 *
 * # Why a repository
 *
 * Wave 5e's `RunTutorialUseCase` keeps state in an in-memory `MutableMap`.
 * That breaks when:
 *
 *  - The server restarts mid-tutorial (player rejoins with no resume).
 *  - The player crosses a proxy boundary (Wave _persist-1, ADR-0068).
 *
 * Wave 5p ships the sequencer with a port so the in-memory default
 * (`InMemoryPlayerTutorialStateRepository`) preserves Wave 5e UX while a
 * future Postgres overlay (Phase 4) restores state across hops.
 *
 * # Single-active-tutorial invariant
 *
 * At most one tutorial sequence per player is active at a time. [save] replaces
 * the existing row; [clear] removes it (called when the sequence completes or
 * is cancelled).
 */
public interface IPlayerTutorialStateRepository {

    public fun find(playerId: PlayerId): TutorialRunnerSnapshot?

    public fun save(playerId: PlayerId, snapshot: TutorialRunnerSnapshot)

    public fun clear(playerId: PlayerId)

    /** Snapshot of every active player. Phase 1 helper for tests / admin commands. */
    public fun all(): Map<PlayerId, TutorialRunnerSnapshot>
}
