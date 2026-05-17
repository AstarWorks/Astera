package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.stats.domain.stats.PlayerStats

public class RecordKillUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        val updated = current.copy(kills = current.kills + 1)
        repository.upsert(updated)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.Kill))
        return Result.success(updated)
    }
}
