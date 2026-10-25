package com.astarworks.astera.core.application.port.outbound

import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId

/**
 * Per-(player, chain) progress store for [com.astarworks.astera.core.domain.quest.QuestChain]
 * (Wave 5s, ADR-0091).
 *
 * # Storage shape
 *
 *  - One row per `(player_id, chain_id)` keyed by both.
 *  - [PlayerQuestProgress.currentStageId] points at the active stage; rewritten
 *    on every stage transition.
 *  - [PlayerQuestProgress.stageProgress] is the cumulative counter against the
 *    current stage's criteria target — reset to 0 on stage transitions, latches
 *    when `completedAt` is set.
 *  - `completedAt` latches on chain completion. Once latched, [upsert] never
 *    unsets it — re-completions are idempotent.
 *
 * # Implementations
 *
 *  - `InMemoryPlayerQuestProgressRepository` at `priority=0` (default, via
 *    `AsteraCorePlugin`).
 *  - `PostgresPlayerQuestProgressRepository` at `priority=100` (overlay, via
 *    `AsteraPersistencePlugin`; backed by V27 migration's `player_quest_progress`
 *    table).
 */
public interface IPlayerQuestProgressRepository {

    /** Returns the stored progress row, or `null` if the player never started this chain. */
    public fun find(playerId: PlayerId, chainId: QuestChainId): PlayerQuestProgress?

    /** Atomically write [progress] for `(playerId, progress.chainId)`. Replaces any existing row. */
    public fun upsert(progress: PlayerQuestProgress)

    /** Snapshot of every chain the player has any row for. */
    public fun allFor(playerId: PlayerId): Collection<PlayerQuestProgress>

    /** Subset of [allFor] whose `completedAt` is null. */
    public fun activeFor(playerId: PlayerId): Collection<PlayerQuestProgress> =
        allFor(playerId).filter { !it.isComplete }
}
