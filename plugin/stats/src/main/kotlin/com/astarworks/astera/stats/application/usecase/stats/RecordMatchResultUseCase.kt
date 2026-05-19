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
 * Records a match result for a single player. Increments `wins` or `losses`
 * on the player's [PlayerStats] aggregate and publishes [StatsUpdated].
 *
 * Win-loss tracking is independent from `matchesPlayed` (recorded by
 * [RecordMatchPlayedUseCase]) so that draws (no winner) still count as a
 * played match without skewing W-L ratios.
 *
 * Wave 5e (ADR-0058 §5): writes through the container facade instead of
 * `.copy()` on a value class.
 */
public class RecordMatchResultUseCase(
    private val repository: IPlayerStatsRepository,
    private val eventBus: IDomainEventBus,
) {

    public fun execute(playerId: PlayerId, outcome: Outcome): Result<PlayerStats, Nothing> {
        val current = repository.find(playerId) ?: PlayerStats.empty(playerId)
        val (key, currentValue) = when (outcome) {
            Outcome.WIN -> Attributes.STAT_WINS to current.wins
            Outcome.LOSS -> Attributes.STAT_LOSSES to current.losses
        }
        current.container.set(key, AttributeValue.NumberValue((currentValue + 1).toDouble()))
        repository.upsert(current)
        val kind = when (outcome) {
            Outcome.WIN -> StatsUpdated.Kind.Win
            Outcome.LOSS -> StatsUpdated.Kind.Loss
        }
        eventBus.publish(StatsUpdated(playerId, kind))
        return Result.success(current)
    }

    /** Per-player outcome of a single match. Draws are recorded by no call to [execute]. */
    public enum class Outcome { WIN, LOSS }
}
