package com.astarworks.astera.stats.application.usecase.stats

import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId
import com.astarworks.astera.stats.application.port.outbound.IPlayerStatsRepository
import com.astarworks.astera.stats.domain.event.StatsUpdated
import com.astarworks.astera.stats.domain.stats.PlayerStats

/**
 * Records a match result for a single player. Increments `wins` or `losses`
 * on the player's [PlayerStats] aggregate and publishes [StatsUpdated].
 *
 * Win-loss tracking is independent from `matchesPlayed` (recorded by
 * [RecordMatchPlayedUseCase]) so that draws (no winner) still count as a
 * played match without skewing W-L ratios.
 *
 * silly-knuth §5: publishes via [IDomainEventBus] only (in-process). External
 * fan-out (Discord match-result feed, Redis pub/sub) lives in Wave 5 and
 * subscribes to the same event downstream.
 */
public class RecordMatchResultUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {

    /**
     * Increment the appropriate counter for [playerId] given [outcome].
     *
     * Always succeeds — failure modes are absent because [PlayerStats] is
     * append-only and the repository is total. Returning [Result] keeps the
     * signature aligned with sibling stats use cases for future extension.
     */
    public fun execute(playerId: PlayerId, outcome: Outcome): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        val updated = when (outcome) {
            Outcome.WIN -> current.copy(wins = current.wins + 1)
            Outcome.LOSS -> current.copy(losses = current.losses + 1)
        }
        repository.upsert(updated)
        val kind = when (outcome) {
            Outcome.WIN -> StatsUpdated.Kind.Win
            Outcome.LOSS -> StatsUpdated.Kind.Loss
        }
        eventBus.publish(StatsUpdated(playerId, kind))
        return Result.success(updated)
    }

    /** Per-player outcome of a single match. Draws are recorded by no call to [execute]. */
    public enum class Outcome { WIN, LOSS }
}
