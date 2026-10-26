package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.advancement.AdvancementId
import com.astarworks.astera.core.domain.advancement.AdvancementProgress
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Per-player progress + completion store for [com.astarworks.astera.core.domain.advancement.CustomAdvancement]
 * (Wave 5p, ADR-0088).
 *
 * # Storage shape
 *
 *  - One row per `(player_id, advancement_id)` keyed by both.
 *  - `current` accumulates against the criteria's dominant leaf counter;
 *    composite criteria's HUD display picks the most-progressed branch
 *    (decided in `ProgressAdvancementUseCase`, not here).
 *  - `completed_at` latches on the first transition `current >= target`.
 *    Once latched, [upsert] never unsets it — re-grants are idempotent.
 *
 * # Implementations
 *
 *  - `InMemoryPlayerAdvancementProgressRepository` at `priority=0` (default,
 *    via `AsteraCorePlugin`).
 *  - `PostgresPlayerAdvancementProgressRepository` at `priority=100` (overlay,
 *    via `AsteraPersistencePlugin`; backed by V26 migration's
 *    `player_advancement_progress` table).
 */
public interface IPlayerAdvancementProgressRepository {

    /** Returns the stored progress, or `null` if the player has never advanced this row. */
    public fun find(playerId: PlayerId, advancementId: AdvancementId): AdvancementProgress?

    /**
     * Atomically write [progress] for `(playerId, advancementId)`. Replaces any
     * existing row. `completedAt` must be either `null` (still in progress) or
     * a fixed timestamp (latched) — callers must never go back from non-null
     * to null.
     */
    public fun upsert(playerId: PlayerId, advancementId: AdvancementId, progress: AdvancementProgress)

    /** Snapshot of every advancement the player has any row for. */
    public fun allFor(playerId: PlayerId): Map<AdvancementId, AdvancementProgress>

    /** Set of advancements the player has completed (convenience over [allFor] for the HUD). */
    public fun completed(playerId: PlayerId): Set<AdvancementId> =
        allFor(playerId).asSequence()
            .filter { it.value.completedAt != null }
            .map { it.key }
            .toSet()
}
