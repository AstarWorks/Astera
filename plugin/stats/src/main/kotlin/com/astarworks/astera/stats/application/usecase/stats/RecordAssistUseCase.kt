package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.core.domain.stats.PlayerStats
import com.astarworks.astera.stats.domain.event.StatsUpdated

/**
 * Increment a player's assist counter. ADR-0063.
 *
 * Mirrors [RecordKillUseCase] — same `find → copy → upsert` pattern, no
 * cross-cutting concerns. Listener wiring is in `AsteraStatsPlugin`.
 */
public class RecordAssistUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(playerId: PlayerId): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        val updated = current.copy(assists = current.assists + 1)
        repository.upsert(updated)
        eventBus.publish(StatsUpdated(playerId, StatsUpdated.Kind.Assist))
        return Result.success(updated)
    }
}
