package com.astarworks.astera.stats.application.eventbus.listeners

import com.astarworks.astera.combat.domain.event.PlayerKilled
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.stats.application.port.outbound.INormaService
import com.astarworks.astera.stats.domain.event.MissionCompleted
import com.astarworks.astera.core.domain.norma.NormaTarget

public class MissionProgressOnKillListener(
    private val normaService: INormaService,
    private val eventBus: IDomainEventBus,
) {
    public suspend fun handle(event: PlayerKilled) {
        if (!event.context.countsForProgression) return // ADR-0235
        val killerId = event.killer ?: return
        val normaForKiller = normaService.activeNormasFor(killerId)

        val newlyCompleted = normaService.recordProgress(killerId, NormaTarget.Kills(1), 1)
        for (progress in newlyCompleted) {
            val norma = normaForKiller.find { it.id == progress.normaId } ?: continue
            eventBus.publish(MissionCompleted(killerId, progress.normaId, norma.reward))
        }
    }
}
