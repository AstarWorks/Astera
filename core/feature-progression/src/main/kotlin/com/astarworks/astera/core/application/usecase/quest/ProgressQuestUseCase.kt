package com.astarworks.astera.core.application.usecase.quest

import com.astarworks.astera.core.application.port.inbound.IQuestService
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.advancement.AdvancementCriteria
import com.astarworks.astera.core.domain.event.QuestChainCompleted
import com.astarworks.astera.core.domain.event.QuestStageCompleted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestError
import com.astarworks.astera.core.domain.quest.QuestStage
import com.astarworks.astera.core.domain.quest.QuestStageOutcome
import java.time.Instant

/**
 * Drives quest stage progression for a player (Wave 5s, ADR-0091).
 *
 * Implements [IQuestService] — listeners in plugins above core (combat kills,
 * item pickups, match wins, recipe crafts) call [startChain] / [progressCurrent]
 * here through the [IQuestService] port.
 *
 * # Stage transition policy
 *
 *  1. Resolve the player's active row for [chainId]. Bail if no row
 *     ([QuestError.NotStarted]) or completed ([QuestError.AlreadyCompleted]).
 *  2. Look up the chain definition. Bail if the stored stage no longer exists
 *     ([QuestError.StageMissing] — soft fail for admin intervention).
 *  3. Add [increment] to the row's `stageProgress`.
 *  4. If still under target → write the new row, return [QuestStageOutcome.StillInProgress].
 *  5. If target reached → dispatch rewards via [GrantQuestRewardUseCase], publish
 *     [QuestStageCompleted]. Then:
 *     - If `followingStageId == null`: latch `completedAt`, publish
 *       [QuestChainCompleted], return [QuestStageOutcome.ChainCompleted].
 *     - Else: write a fresh row pointing at the next stage with
 *       `stageProgress = 0`, return [QuestStageOutcome.StageAdvanced].
 *
 * # Counter semantics
 *
 * Phase 1 PoC stores a single scalar `stageProgress` per row — composite
 * criteria (`AllOf` / `AnyOf`) use the dominant-leaf target (least-progressed
 * for AllOf, most-progressed for AnyOf), matching `ProgressAdvancementUseCase`'s
 * HUD simplification. Phase 4 may add per-leaf sub-rows like the advancement
 * use case if composite quests become common.
 *
 * # Threading & idempotence
 *
 * Paper main-thread by convention. Repeat invocations after the chain is
 * complete short-circuit to [QuestError.AlreadyCompleted] (no state change,
 * no event re-publish, no double reward).
 */
public class ProgressQuestUseCase(
    private val registry: IQuestChainRegistry,
    private val repository: IPlayerQuestProgressRepository,
    private val rewardGrant: GrantQuestRewardUseCase,
    private val startChainUseCase: StartQuestChainUseCase,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val clock: () -> Instant = { Instant.now() },
) : IQuestService {

    override suspend fun startChain(
        playerId: PlayerId,
        chainId: QuestChainId,
    ): Result<PlayerQuestProgress, QuestError> = startChainUseCase.start(playerId, chainId)

    override suspend fun progressCurrent(
        playerId: PlayerId,
        chainId: QuestChainId,
        increment: Int,
    ): Result<QuestStageOutcome, QuestError> {
        if (increment <= 0) return Result.failure(QuestError.InvalidIncrement(increment))

        val chain = registry.find(chainId)
            ?: return Result.failure(QuestError.UnknownChain(chainId))

        val current = repository.find(playerId, chainId)
            ?: return Result.failure(QuestError.NotStarted(chainId))

        if (current.isComplete) {
            return Result.failure(QuestError.AlreadyCompleted(chainId))
        }

        val stage = chain.stage(current.currentStageId)
            ?: return Result.failure(QuestError.StageMissing(chainId, current.currentStageId))

        val target = dominantTarget(stage.criteria)
        val newCount = current.stageProgress + increment

        if (newCount < target) {
            val updated = current.copy(stageProgress = newCount, updatedAt = clock())
            repository.upsert(updated)
            return Result.success(QuestStageOutcome.StillInProgress(updated))
        }

        // Stage completed — dispatch rewards before transitioning so subscribers
        // see the canonical (rewards-granted, then stage-completed) ordering.
        rewardGrant.grant(playerId, chainId, stage.id, stage.rewards)

        val tick = scheduler.currentTick()
        val nextStageId = stage.followingStageId

        events.publish(
            QuestStageCompleted(
                playerId = playerId,
                chainId = chainId,
                completedStageId = stage.id,
                nextStageId = nextStageId,
                atTick = tick,
            ),
        )

        return if (nextStageId == null) {
            val now = clock()
            val completed = current.copy(
                stageProgress = target,
                completedAt = now,
                updatedAt = now,
            )
            repository.upsert(completed)
            events.publish(
                QuestChainCompleted(
                    playerId = playerId,
                    chainId = chainId,
                    atTick = tick,
                ),
            )
            Result.success(QuestStageOutcome.ChainCompleted(stage, completed))
        } else {
            val nextStage = chain.stage(nextStageId)
                ?: return Result.failure(QuestError.StageMissing(chainId, nextStageId))
            val advanced = current.copy(
                currentStageId = nextStageId,
                stageProgress = 0,
                updatedAt = clock(),
            )
            repository.upsert(advanced)
            Result.success(QuestStageOutcome.StageAdvanced(stage, nextStage, advanced))
        }
    }

    override suspend fun activeChains(playerId: PlayerId): Collection<PlayerQuestProgress> =
        repository.activeFor(playerId)

    override suspend fun allChains(playerId: PlayerId): Collection<PlayerQuestProgress> =
        repository.allFor(playerId)

    /**
     * Compute the scalar target for a stage's [criteria] using the dominant
     * leaf (least-progressed for AllOf, most-progressed for AnyOf, the leaf
     * itself otherwise). Mirrors `ProgressAdvancementUseCase.pickDominantTarget`
     * semantics so YAML authors get consistent HUD numbers across both systems.
     *
     * Phase 1 simplification: composite branches all collapse to a single
     * scalar — true tree evaluation lands in Wave 6a if the data demands.
     */
    private fun dominantTarget(criteria: AdvancementCriteria): Int = when (criteria) {
        is AdvancementCriteria.KillCount -> criteria.target
        is AdvancementCriteria.CurrencyEarned -> criteria.amount.toInt().coerceAtLeast(1)
        is AdvancementCriteria.MatchesWon -> criteria.count
        is AdvancementCriteria.ItemAcquired -> 1
        is AdvancementCriteria.AllOf -> criteria.children.maxOf { dominantTarget(it) }
        is AdvancementCriteria.AnyOf -> criteria.children.minOf { dominantTarget(it) }
    }
}
