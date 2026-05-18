package com.astarworks.astera.combat.application.usecase.cost

import com.astarworks.astera.combat.application.port.outbound.IManaPoolService
import com.astarworks.astera.combat.domain.event.ManaConsumed
import com.astarworks.astera.combat.domain.event.ManaConsumptionSource
import com.astarworks.astera.combat.domain.resource.ManaError
import com.astarworks.astera.combat.domain.resource.ManaPool
import com.astarworks.astera.core.application.port.outbound.IDomainEventBus
import com.astarworks.astera.core.domain.Result
import com.astarworks.astera.core.domain.player.PlayerId

/**
 * Atomic mana-debit operation.
 *
 * Asks [IManaPoolService] to deduct [amount] from the player's pool, and on
 * success publishes a [ManaConsumed] domain event (HUD / audit consumers
 * subscribe to it). On failure (`Insufficient` or `PlayerNotInitialized`)
 * **no event is published** and the failure propagates to the caller —
 * typically [com.astarworks.astera.combat.application.usecase.skill.CastSkillUseCase]
 * which maps it to `CastSkillError.InsufficientMana`.
 *
 * Splitting this out (rather than calling `IManaPoolService.consume`
 * directly from CastSkill) keeps the "consume + event" pair colocated and
 * lets future cost sources (Toggle, Buff upkeep) share the publish step.
 */
public class ConsumeManaUseCase(
    private val manaService: IManaPoolService,
    private val eventBus: IDomainEventBus,
) {
    public fun execute(
        playerId: PlayerId,
        source: ManaConsumptionSource,
        amount: Long,
    ): Result<ManaPool, ManaError> = when (val outcome = manaService.consume(playerId, amount)) {
        is Result.Success -> {
            eventBus.publish(
                ManaConsumed(
                    playerId = playerId,
                    amount = amount,
                    remaining = outcome.value.current,
                    source = source,
                ),
            )
            Result.success(outcome.value)
        }
        is Result.Failure -> Result.failure(outcome.error)
    }
}
