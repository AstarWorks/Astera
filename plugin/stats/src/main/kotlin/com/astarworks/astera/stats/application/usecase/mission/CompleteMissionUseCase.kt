package com.astarworks.astera.stats.application.usecase.mission

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.core.domain.norma.MissionProgress
import com.astarworks.astera.core.domain.norma.NormaId

/**
 * Manual admin command: mark a norma as completed for a player.
 *
 * Listener-based completion (via [com.astarworks.astera.stats.application.eventbus.listeners.MissionProgressOnKillListener])
 * handles the automatic flow. This use case is for /astera stats complete admin usage.
 */
public class CompleteMissionUseCase(
    private val normaService: INormaService,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(playerId: PlayerId, normaId: NormaId): Result<MissionProgress, CompleteMissionError> {
        val allNormas = normaService.activeNormasFor(playerId)
        val norma = allNormas.find { it.id == normaId }
            ?: return Result.failure(CompleteMissionError.UnknownNorma)

        val existing = normaService.progress(playerId).find { it.normaId == normaId }
        if (existing?.completed == true) {
            return Result.failure(CompleteMissionError.AlreadyCompleted)
        }

        val updated = normaService.markComplete(playerId, normaId)
            ?: return Result.failure(CompleteMissionError.UnknownNorma)

        eventBus.publish(MissionCompleted(playerId, normaId, norma.reward))
        return Result.success(updated)
    }
}

public sealed class CompleteMissionError {
    public data object UnknownNorma : CompleteMissionError()
    public data object NotEligible : CompleteMissionError()
    public data object AlreadyCompleted : CompleteMissionError()
}
