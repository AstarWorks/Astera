package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.stats.domain.stats.PlayerStats

/**
 * Increments `matchesPlayed` on a single player's [PlayerStats] aggregate and
 * publishes [StatsUpdated] with kind [StatsUpdated.Kind.MatchPlayed].
 *
 * Split from [RecordMatchResultUseCase] so that participation (which counts
 * draws and forfeits) is tracked independently from win-loss outcomes.
 *
 * silly-knuth §5: publishes via [IDomainEventBus] only.
 */
public class RecordMatchPlayedUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        val updated = current.copy(matchesPlayed = current.matchesPlayed + 1)
        repository.upsert(updated)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.MatchPlayed))
        return Result.success(updated)
    }
}
