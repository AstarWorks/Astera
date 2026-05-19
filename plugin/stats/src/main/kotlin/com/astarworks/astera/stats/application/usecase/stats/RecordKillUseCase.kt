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
 * Wave 5e rewrite (ADR-0058 §5): mutates the underlying [PlayerStats]
 * container directly instead of `copy()`-ing a value class. The repository
 * (in-memory or Postgres-overlay) hands us the same container the rest of
 * the player's resources (`Wallet`, `ManaPool`) share, so an increment here
 * is observable to every other facade.
 */
public class RecordKillUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        current.container.set(
            Attributes.STAT_KILLS,
            AttributeValue.NumberValue((current.kills + 1).toDouble()),
        )
        repository.upsert(current)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.Kill))
        return Result.success(current)
    }
}
