package com.astarworks.astera.core.application.usecase.quest

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerQuestProgressRepository
import com.astarworks.astera.core.application.port.outbound.IQuestChainRegistry
import com.astarworks.astera.core.application.port.outbound.IScheduler
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.event.QuestChainStarted
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.quest.PlayerQuestProgress
import com.astarworks.astera.core.domain.quest.QuestChainId
import com.astarworks.astera.core.domain.quest.QuestError
import java.time.Instant

/**
 * Activates a [com.astarworks.astera.core.domain.quest.QuestChain] for a
 * player (Wave 5s, ADR-0091).
 *
 * # Pre-conditions
 *
 *  - [chainId] must be in [registry].
 *  - The player must not already have an active row for the chain (re-starting
 *    would lose progress — idempotency would have to be a fresh-start semantic
 *    elsewhere).
 *  - The player must not have completed the chain (`completedAt` is non-null).
 *
 * # Effects
 *
 *  1. Write a [PlayerQuestProgress] row pointing at the chain's entry stage
 *     with `stageProgress = 0` and `completedAt = null`.
 *  2. Publish [QuestChainStarted] with the entry stage id.
 *
 * # Threading
 *
 * Paper main-thread by convention. Wave _persist-1 (ADR-0068) guarantees the
 * persistence overlay's repository upsert is synchronous from a Paper-tick
 * perspective.
 */
public class StartQuestChainUseCase(
    private val registry: IQuestChainRegistry,
    private val repository: IPlayerQuestProgressRepository,
    private val events: IDomainEventBus,
    private val scheduler: IScheduler,
    private val clock: () -> Instant = { Instant.now() },
) {

    public fun start(
        playerId: PlayerId,
        chainId: QuestChainId,
    ): Result<PlayerQuestProgress, QuestError> {
        val chain = registry.find(chainId)
            ?: return Result.failure(QuestError.UnknownChain(chainId))

        repository.find(playerId, chainId)?.let { existing ->
            return if (existing.isComplete) {
                Result.failure(QuestError.AlreadyCompleted(chainId))
            } else {
                Result.failure(QuestError.AlreadyStarted(chainId))
            }
        }

        val entry = chain.entryStage
        val progress = PlayerQuestProgress(
            playerId = playerId,
            chainId = chainId,
            currentStageId = entry.id,
            stageProgress = 0,
            completedAt = null,
            updatedAt = clock(),
        )
        repository.upsert(progress)
        events.publish(
            QuestChainStarted(
                playerId = playerId,
                chainId = chainId,
                entryStageId = entry.id,
                atTick = scheduler.currentTick(),
            ),
        )
        return Result.success(progress)
    }
}
