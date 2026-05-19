package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.attribute.AttributeValue
import com.astarworks.astera.core.domain.attribute.Attributes
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.stats.domain.event.StatsUpdated

/**
 * Increment a player's assist counter. ADR-0063 + Wave 5e ADR-0058 §5
 * container-facade rewrite.
 */
public class RecordAssistUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        current.container.set(
            Attributes.STAT_ASSISTS,
            AttributeValue.NumberValue((current.assists + 1).toDouble()),
        )
        repository.upsert(current)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.Assist))
        return Result.success(current)
    }
}
