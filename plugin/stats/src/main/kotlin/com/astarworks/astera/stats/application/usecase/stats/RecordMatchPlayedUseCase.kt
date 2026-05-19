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
 * Increments `matchesPlayed` on a single player's [PlayerStats] aggregate and
 * publishes [StatsUpdated] with kind [StatsUpdated.Kind.MatchPlayed].
 *
 * Split from [RecordMatchResultUseCase] so that participation (which counts
 * draws and forfeits) is tracked independently from win-loss outcomes.
 *
 * Wave 5e (ADR-0058 §5): writes through the container facade instead of
 * `.copy()` on a value class.
 */
public class RecordMatchPlayedUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        current.container.set(
            Attributes.STAT_MATCHES_PLAYED,
            AttributeValue.NumberValue((current.matchesPlayed + 1).toDouble()),
        )
        repository.upsert(current)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.MatchPlayed))
        return Result.success(current)
    }
}
