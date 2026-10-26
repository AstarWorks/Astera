package com.astarworks.astera.core.domain.quest

import com.astarworks.astera.core.domain.player.PlayerId
import java.time.Instant

/**
 * Per-(player, chain) row tracking which stage the player is currently on,
 * and how far through that stage's criteria counter they've progressed
 * (Wave 5s, ADR-0091).
 *
 * Stored row-per-(player, chain) in `player_quest_progress` (V27 migration).
 *
 *  - [currentStageId] is the stage the player is currently working through.
 *    When a stage completes, the row is overwritten with the next stage's id
 *    (or marked complete if the stage's `followingStageId` is null).
 *  - [stageProgress] is the cumulative counter against the current stage's
 *    [com.astarworks.astera.core.domain.advancement.AdvancementCriteria]
 *    dominant-leaf target (mirrors `AdvancementProgress.current`).
 *  - [completedAt] latches once the chain's terminal stage clears. Once
 *    non-null it never unsets — re-grants are idempotent.
 *  - [updatedAt] is bookkeeping for cross-server cache invalidation and
 *    quest log "last activity" display.
 */
public data class PlayerQuestProgress(
    public val playerId: PlayerId,
    public val chainId: QuestChainId,
    public val currentStageId: QuestStageId,
    public val stageProgress: Int,
    public val completedAt: Instant? = null,
    public val updatedAt: Instant = Instant.now(),
) {
    public val isComplete: Boolean get() = completedAt != null
}
